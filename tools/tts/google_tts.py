"""Speech synthesis backends: Google Cloud Text-to-Speech, and a fake for tests and rehearsals.

The Google backend uses the google-cloud-texttospeech client with Application Default
Credentials (the same client as the HablaDeutsch generator), which refreshes tokens
itself; tokens are never read or logged here. It requests LINEAR16, whose response is
a complete WAV file, so the lossless master is the response as received.
"""
from __future__ import annotations

import io
import math
import os
import random
import threading
import time
import wave
from dataclasses import dataclass


# gRPC logs two INFO lines each time the encoder subprocess forks while a request is in
# flight. The child execs straight away, so gRPC's fork handlers are not needed. This has
# to be set before the Google client imports grpc.
os.environ.setdefault("GRPC_ENABLE_FORK_SUPPORT", "false")
os.environ.setdefault("GRPC_VERBOSITY", "ERROR")


class TransientSynthesisError(Exception):
    """Worth retrying: rate limits, timeouts, temporary server errors."""


class FatalSynthesisError(Exception):
    """Stops the whole run: authentication, permission, billing or invalid input."""


@dataclass(frozen=True)
class SynthesisSettings:
    voice: str
    language: str = "de-DE"
    speaking_rate: float = 1.0
    sample_rate_hertz: int = 0  # 0 = the voice's natural rate

    def as_dict(self) -> dict:
        return {
            "voice": self.voice,
            "language": self.language,
            "audioEncoding": "LINEAR16",
            "speakingRate": self.speaking_rate,
            "sampleRateHertz": self.sample_rate_hertz,
        }


class GoogleTts:
    name = "google"

    def __init__(self, quota_project: str | None = None):
        try:
            from google.cloud import texttospeech
        except ImportError as exc:
            raise FatalSynthesisError(
                "google-cloud-texttospeech is not installed: pip install -r tools/tts/requirements.txt") from exc
        self._tts = texttospeech
        options = {"quota_project_id": quota_project} if quota_project else None
        self._local = threading.local()
        self._options = options
        self.client()  # fail early on missing credentials

    def client(self):
        if not hasattr(self._local, "client"):
            try:
                self._local.client = self._tts.TextToSpeechClient(client_options=self._options)
            except Exception as exc:  # DefaultCredentialsError and friends
                raise FatalSynthesisError(f"cannot create a Text-to-Speech client: {exc}") from exc
        return self._local.client

    def list_voices(self, language: str) -> list[dict]:
        response = self._call(lambda: self.client().list_voices(language_code=language))
        return [{
            "name": v.name,
            "languageCodes": list(v.language_codes),
            "ssmlGender": self._tts.SsmlVoiceGender(v.ssml_gender).name,
            "naturalSampleRateHertz": v.natural_sample_rate_hertz,
        } for v in response.voices]

    def synthesize(self, value: str, is_ssml: bool, settings: SynthesisSettings) -> bytes:
        tts = self._tts
        request_input = tts.SynthesisInput(ssml=value) if is_ssml else tts.SynthesisInput(text=value)
        config = {"audio_encoding": tts.AudioEncoding.LINEAR16, "speaking_rate": settings.speaking_rate}
        if settings.sample_rate_hertz:
            config["sample_rate_hertz"] = settings.sample_rate_hertz
        response = self._call(lambda: self.client().synthesize_speech(
            input=request_input,
            voice=tts.VoiceSelectionParams(language_code=settings.language, name=settings.voice),
            audio_config=tts.AudioConfig(**config),
        ))
        if not response.audio_content:
            raise TransientSynthesisError("Google returned no audio")
        return response.audio_content

    @staticmethod
    def _call(fn):
        from google.api_core import exceptions as gexc
        try:
            from google.auth import exceptions as auth_exc
            auth_errors = (auth_exc.DefaultCredentialsError, auth_exc.RefreshError)
        except ImportError:
            auth_errors = ()
        transient = (gexc.TooManyRequests, gexc.ServiceUnavailable, gexc.DeadlineExceeded,
                     gexc.InternalServerError, gexc.Aborted, gexc.GatewayTimeout)
        fatal = (gexc.Unauthenticated, gexc.PermissionDenied, gexc.InvalidArgument,
                 gexc.FailedPrecondition, gexc.NotFound)
        try:
            return fn()
        except transient as exc:
            raise TransientSynthesisError(f"{type(exc).__name__}: {exc.message}") from exc
        except fatal as exc:
            raise FatalSynthesisError(f"{type(exc).__name__}: {exc.message}") from exc
        except auth_errors as exc:
            raise FatalSynthesisError(f"credentials: {exc}") from exc


class FakeTts:
    """Deterministic tones shaped like speech (silence, voiced span, silence), so the
    whole pipeline can be rehearsed without credentials or charges."""
    name = "fake"

    def __init__(self, rate: int = 24000, fail_first: dict | None = None):
        self.rate = rate
        self.calls: list[str] = []
        self._fail_first = dict(fail_first or {})  # text -> number of transient failures
        self._lock = threading.Lock()

    def list_voices(self, language: str) -> list[dict]:
        return [{"name": f"{language}-Fake-A", "languageCodes": [language], "ssmlGender": "NEUTRAL",
                 "naturalSampleRateHertz": self.rate}]

    def synthesize(self, value: str, is_ssml: bool, settings: SynthesisSettings) -> bytes:
        with self._lock:
            self.calls.append(value)
            if self._fail_first.get(value, 0) > 0:
                self._fail_first[value] -= 1
                raise TransientSynthesisError("fake 503")
        seconds = 0.3 + 0.06 * len(value)
        freq = 180 + (sum(map(ord, value)) % 200)
        n, lead = int(seconds * self.rate), int(0.35 * self.rate)
        frames = bytearray()
        for i in range(n + 2 * lead):
            t = i - lead
            envelope = math.sin(math.pi * t / n) if 0 <= t < n else 0.0
            frames += int(9000 * envelope * math.sin(2 * math.pi * freq * i / self.rate)).to_bytes(2, "little", signed=True)
        out = io.BytesIO()
        with wave.open(out, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(self.rate)
            w.writeframes(bytes(frames))
        return out.getvalue()


class RateLimiter:
    """At most [per_minute] requests per minute across all worker threads."""

    def __init__(self, per_minute: int):
        self._interval = 60.0 / per_minute if per_minute > 0 else 0.0
        self._next = 0.0
        self._lock = threading.Lock()

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            slot = max(now, self._next)
            self._next = slot + self._interval
        if slot > now:
            time.sleep(slot - now)


def with_retries(fn, attempts: int = 6, base_delay: float = 1.0, max_delay: float = 30.0, sleep=time.sleep):
    """Run [fn], retrying transient failures with bounded exponential backoff and jitter."""
    for attempt in range(attempts):
        try:
            return fn()
        except TransientSynthesisError:
            if attempt == attempts - 1:
                raise
            sleep(min(max_delay, base_delay * 2 ** attempt) * (0.5 + random.random() / 2))
