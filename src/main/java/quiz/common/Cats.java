package quiz.common;

/** ANSI colours and cat art shared by every question. */
public final class Cats {

    private static final String E = String.valueOf((char) 27);
    public static final String R = E + "[0m";
    public static final String P = E + "[35m";
    public static final String Y = E + "[33m";
    public static final String C = E + "[36m";
    public static final String G = E + "[32m";
    public static final String RED = E + "[31m";

    private Cats() { }

    public static void cat(String caption) {
        System.out.println(P + "  /\\_/\\ " + R);
        System.out.println(Y + " ( o.o )" + R);
        System.out.println(C + "  > ^ < " + R + "  " + caption);
        System.out.println();
    }

    public static void answer(String text) {
        System.out.println();
        System.out.println(G + ">> " + text + R);
    }

    public static void boom(String text) {
        System.out.println(RED + "!! " + text + R);
    }
}
