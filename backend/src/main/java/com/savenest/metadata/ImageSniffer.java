package com.savenest.metadata;

/** Verifies that image bytes match the declared raster MIME type (magic numbers). SVG is never accepted. */
public final class ImageSniffer {

    private ImageSniffer() {
    }

    public static String detect(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' && b[8] == 'a' && b[9] == 'v' && b[10] == 'i') {
            return "image/avif";
        }
        return null;
    }

    public static boolean matches(byte[] body, String declared) {
        String d = detect(body);
        return d != null && d.equals(declared);
    }
}
