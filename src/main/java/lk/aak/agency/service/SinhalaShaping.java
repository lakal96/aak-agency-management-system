package lk.aak.agency.service;

/**
 * Minimal Sinhala text preparation for PDF output. OpenPDF writes characters one by one and has no
 * complex-script shaping, but Sinhala writes the "kombuva" vowel signs (e, ee, ai and the two-part
 * o, oo, au) to the LEFT of the consonant they follow in Unicode order. Without moving them the
 * signs would print after the consonant. This puts them in visual order; conjunct forms such as
 * rakaransaya (ශ්‍රී) are still printed as separate signs, which stays readable.
 */
final class SinhalaShaping {

    private static final char VIRAMA = '්';
    private static final char ZWJ = '‍';
    private static final char E = 'ෙ';
    private static final char EE = 'ේ';
    private static final char AI = 'ෛ';
    private static final char O = 'ො';
    private static final char OO = 'ෝ';
    private static final char AU = 'ෞ';
    private static final char AA = 'ා';
    private static final char AU_LENGTH_MARK = 'ෟ';

    private SinhalaShaping() {
    }

    static boolean containsSinhala(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '඀' && c <= '෿') {
                return true;
            }
        }
        return false;
    }

    private static boolean isConsonant(char c) {
        return c >= 'ක' && c <= 'ෆ';
    }

    /** The text with left-hand vowel signs moved in front of their consonant cluster. */
    static String toVisualOrder(String text) {

        if (text == null || !containsSinhala(text)) {
            return text;
        }

        StringBuilder out = new StringBuilder(text.length() + 4);
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (!isConsonant(c)) {
                out.append(c);
                i++;
                continue;
            }

            // consonant (+ virama + ZWJ + consonant)* is one cluster
            int end = i + 1;
            while (end + 2 < text.length()
                    && text.charAt(end) == VIRAMA
                    && text.charAt(end + 1) == ZWJ
                    && isConsonant(text.charAt(end + 2))) {
                end += 3;
            }

            String cluster = text.substring(i, end);
            char sign = end < text.length() ? text.charAt(end) : 0;

            switch (sign) {
                case E, EE, AI -> out.append(sign).append(cluster);
                case O -> out.append(E).append(cluster).append(AA);
                case OO -> out.append(E).append(cluster).append(AA).append(VIRAMA);
                case AU -> out.append(E).append(cluster).append(AU_LENGTH_MARK);
                default -> {
                    out.append(cluster);
                    i = end;
                    continue;
                }
            }
            i = end + 1;
        }

        return out.toString();
    }
}
