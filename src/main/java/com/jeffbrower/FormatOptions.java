package com.jeffbrower;

/**
 * @see <a href="https://prettier.io/docs/options">Prettier docs</a>
 *
 * Prettier also has 'singleQuote', but this proved difficult, so we'll preserve all quotes as-is.
 */
public class FormatOptions {
   /**
    * Specify the line length that the printer will wrap on.
    *
    * @see <a href="https://prettier.io/docs/options#print-width">Prettier docs</a>
    */
   public int printWidth = 80;

   /**
    * Specify the number of spaces per indentation-level.
    *
    * @see <a href="https://prettier.io/docs/options#tab-width">Prettier docs</a>
    */
   public int tabWidth = 2;

   /**
    * Indent lines with tabs instead of spaces.
    *
    * @see <a href="https://prettier.io/docs/options#tabs">Prettier docs</a>
    */
   public boolean useTabs = true;

   /**
    * Put the `>` of a multi-line element at the end of the last line instead of being alone on the
    * next line (does not apply to self closing elements).
    *
    * @see <a href="https://prettier.io/docs/options#bracket-line">Prettier docs</a>
    */
   public boolean bracketSameLine = false;

   /**
    * Specify the line endings. Valid values are LF, CRLF, and SYSTEM (use the system default).
    *
    * @see <a href="https://prettier.io/docs/options#end-of-line">Prettier docs</a>
    */
   public LineEnding endOfLine = LineEnding.LF;

   /**
    * Enforce single attribute per line.
    *
    * @see <a href="https://prettier.io/docs/options#single-attribute-per-line">Prettier docs</a>
    */
   public boolean singleAttributePerLine = false;
}
