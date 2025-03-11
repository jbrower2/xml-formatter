package com.jeffbrower;

/** @see https://prettier.io/docs/options */
public class FormatOptions {
   public int printWidth = 80;
   public int tabWidth = 2;
   public boolean useTabs = false;
   // prettier also has 'singleQuote', but we'll preserve all quotes as-is
   public boolean bracketSameLine = false;
   public LineEnding endOfLine = LineEnding.LF;
   public boolean singleAttributePerLine = false;
}
