import unittest
from import_corpus import canonical, sentences, target_tokens


class SentenceImportTest(unittest.TestCase):
    def test_one_sentence_with_target_can_be_selected_from_a_paragraph(self):
        source = 'Er klingelte. Es machte ihm jedoch niemand auf.\nNiemand war zu Hause.'
        result = next(s for s in sentences(source) if target_tokens('jedoch', s))
        self.assertEqual('Es machte ihm jedoch niemand auf.', result)

    def test_abbreviations_ordinals_and_initials_are_not_sentence_boundaries(self):
        for text in [
            'St. Martin ist ein gutes Beispiel für Nächstenliebe.',
            'Am 1. September ist Weltfriedenstag.',
            'Erwachsene brauchen ca. acht Stunden Schlaf pro Tag.',
            'Die Klage gegen Robert H. wurde abgewiesen.',
            'Mr. Gibbs ist Rubens Klassenlehrer.',
        ]:
            self.assertEqual([text], sentences(text))

    def test_question_and_exclamation_boundaries(self):
        self.assertEqual(['Kommen Sie zu uns?', 'Das ist zu viel.', 'Hilfe!'], sentences('Kommen Sie zu uns?\nDas ist zu viel. Hilfe!'))

    def test_targets_are_whole_words_and_preserve_surface_case(self):
        self.assertEqual([], target_tokens('an', 'Anna kommt.'))
        self.assertEqual(['Der'], target_tokens('der, die, das', 'Der Mann kommt.'))

    def test_clear_source_typos_and_variants_are_normalized(self):
        self.assertEqual('Hemd', canonical('Hbemd'))
        self.assertEqual('Teilnehmer', canonical('Tellnehmer'))
        self.assertEqual('befinden', canonical('befinden (sich)'))
        self.assertEqual('Welle', canonical('Welle', 1641))
        self.assertEqual('Weile', canonical('Welle', 1807))


if __name__ == '__main__':
    unittest.main()
