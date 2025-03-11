package com.jeffbrower;

public enum LineEnding {
   SYSTEM(System.lineSeparator()),
   LF("\n"),
   CRLF("\r\n");

   public final String string;

   private LineEnding(final String string) {
      this.string = string;
   }
}
