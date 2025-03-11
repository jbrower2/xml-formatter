package com.jeffbrower;

public class Logger {
   private static final boolean DEBUG = System.getProperty("formatter.debug", "false").equals("true");

   public static void log(final String message) {
      if (DEBUG) {
         System.out.println(message);
      }
   }

   public static String stringify(final String s) {
      final StringBuilder b = new StringBuilder("\"");
      for (final char c : s.toCharArray()) {
         switch (c) {
            case '\n':
               b.append("\\n");
               break;
            case '\r':
               b.append("\\r");
               break;
            case '\t':
               b.append("\\t");
               break;
            default:
               if (c >= ' ' && c < 0x7f) {
                  b.append(c);
               } else {
                  b.append("\\u{").append(Integer.toHexString(c)).append('}');
               }
               break;
         }
      }
      return b.append('"').toString();
   }
}
