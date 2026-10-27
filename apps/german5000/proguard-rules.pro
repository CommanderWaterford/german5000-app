# German5000 release shrinking rules.
# Room, WorkManager, Billing and kotlinx.serialization ship their own consumer rules.

# Prompt snapshots are stored as JSON with polymorphic kotlinx.serialization serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.vocab5000.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.vocab5000.**$$serializer { *; }

# Workers are instantiated reflectively by WorkManager.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
