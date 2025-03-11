package com.jeffbrower;

/**
 * @see https://prettier.io/docs/options
 *
 * Prettier also has 'singleQuote', but this proved difficult, so we'll preserve all quotes as-is.
 */
public class FormatOptions {
   /**
    * Specify the line length that the printer will wrap on.
    *
    * @see https://prettier.io/docs/options#print-width
    */
   public int printWidth = 80;

   /**
    * Specify the number of spaces per indentation-level.
    *
    * @see https://prettier.io/docs/options#tab-width
    */
   public int tabWidth = 2;

   /**
    * Indent lines with tabs instead of spaces.
    *
    * @see https://prettier.io/docs/options#tabs
    */
   public boolean useTabs = true;

   /**
    * Put the `>` of a multi-line element at the end of the last line instead of being alone on the
    * next line (does not apply to self closing elements).
    *
    * @see https://prettier.io/docs/options#bracket-line
    */
   public boolean bracketSameLine = false;

   /**
    * Specify the line endings. Valid values are LF, CRLF, and SYSTEM (use the system default).
    *
    * @see https://prettier.io/docs/options#end-of-line
    */
   public LineEnding endOfLine = LineEnding.LF;

   /**
    * Enforce single attribute per line.
    *
    * @see https://prettier.io/docs/options#single-attribute-per-line
    */
   public boolean singleAttributePerLine = false;
}
