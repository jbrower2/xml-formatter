package com.jeffbrower;

public enum LineEnding {
   /** Use the system line endings. */
   SYSTEM(System.lineSeparator()),

   /** Unix/macOS line endings (`\n`). */
   LF("\n"),

   /** Windows line endings (`\r\n`). */
   CRLF("\r\n");

   /** The line ending as a string. */
   public final String string;

   private LineEnding(final String string) {
      this.string = string;
   }
}
