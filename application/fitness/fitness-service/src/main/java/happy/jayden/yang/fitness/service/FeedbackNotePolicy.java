package happy.jayden.yang.fitness.service;

final class FeedbackNotePolicy {

  private FeedbackNotePolicy() {}

  /**
   * The feedback contract rejects U+0000 and treats Unicode White_Space code points plus U+FEFF as
   * whitespace. A valid OTHER note therefore contains at least one code point outside this explicit
   * set.
   */
  static boolean hasNonWhitespaceCodePoint(String value) {
    if (value == null || value.codePoints().anyMatch(codePoint -> codePoint == 0)) {
      return false;
    }
    return value
        .codePoints()
        .anyMatch(
            codePoint ->
                !((codePoint >= 0x0009 && codePoint <= 0x000D)
                    || codePoint == 0x0020
                    || codePoint == 0x0085
                    || codePoint == 0x00A0
                    || codePoint == 0x1680
                    || (codePoint >= 0x2000 && codePoint <= 0x200A)
                    || codePoint == 0x2028
                    || codePoint == 0x2029
                    || codePoint == 0x202F
                    || codePoint == 0x205F
                    || codePoint == 0x3000
                    || codePoint == 0xFEFF));
  }
}
