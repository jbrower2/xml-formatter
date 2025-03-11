package com.jeffbrower;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;

public class Formatter implements Closeable {
   public static final boolean DEBUG = System.getProperty("formatter.debug", "false").equals("true");

   private final PushbackReader r;
   private final Writer w;
   private final FormatOptions o;

   public Formatter(final PushbackReader r, final Writer w, final FormatOptions o) {
      this.r = r;
      this.w = w;
      this.o = o;
   }

   @Override
   public void close() throws IOException {
      IOException e = null;

      try {
         r.close();
      } catch (final IOException e1) {
         e = e1;
      }

      try {
         w.close();
      } catch (final IOException e2) {
         if (e != null) {
            System.err.println(e);
         }
         e = e2;
      }

      if (e != null) {
         throw e;
      }
   }

   ////////////////////////////////////////////////////////////////
   //////////////////////////// COMMON ////////////////////////////
   ////////////////////////////////////////////////////////////////

   public boolean parseAny() throws IOException {
      final int c = r.read();
      if (c == -1) {
         return false;
      }

      if (c == '<') {
         parseTag();
         return true;
      }

      r.unread(c);

      final String rawCharData = parseCharData(null);

      final String charDataTrimStart = rawCharData.stripLeading();
      if (charDataTrimStart.isEmpty()) {
         if (hasMultipleNewlines(rawCharData)) {
            writeNewLine();
         } else {
            log("empty char data: " + stringify(rawCharData));
         }
         return true;
      }

      final String whitespacePrefix = rawCharData.substring(0, rawCharData.length() - charDataTrimStart.length());
      if (hasMultipleNewlines(whitespacePrefix)) {
         writeNewLine();
      }

      final String charData = charDataTrimStart.stripTrailing();
      writeText(charData);

      final String whitespaceSuffix = charDataTrimStart.substring(charData.length());
      if (hasMultipleNewlines(whitespaceSuffix)) {
         writeNewLine();
      }

      return true;
   }

   private String parseCharData(final String end) throws IOException {
      // https://www.w3.org/TR/xml11/#NT-CharData
      // note: this implementation allows CharData to contain References

      final StringBuilder b = new StringBuilder();
      while (true) {
         if (
            end != null &&
            b.length() >= end.length() &&
            b.substring(b.length() - end.length()).equals(end)
         ) {
            b.setLength(b.length() - end.length());
            break;
         }

         final int c = r.read();
         if (c == -1) {
            break;
         }

         if (c == '<') {
            r.unread('<');
            break;
         }

         if (c == '&') {
            b.append(parseReference());
            continue;
         }

         b.append((char) c);
      }

      return b.toString();
   }

   private static boolean hasMultipleNewlines(final String s) {
      boolean foundOne = false;
      for (int i = 0; i < s.length(); i++) {
         if (s.charAt(i) == '\n') {
            if (foundOne) {
               return true;
            }
            foundOne = true;
         }
      }
      return false;
   }

   private String parseReference() throws IOException {
      // precondition: already read: "&"
      // https://www.w3.org/TR/xml11/#NT-Reference

      int c = r.read();

      if (c != '#') {
         r.unread(c);
         final String name = parseName();

         c = r.read();
         if (c != ';') {
            throw new IllegalStateException("Expected end of reference: " + c);
         }

         return "&" + name + ";";
      }

      // precondition: already read "&#"
      final StringBuilder b = new StringBuilder("&#");
      final IntPredicate digitTest;

      c = r.read();
      if (c == 'x') {
         b.append('x');
         digitTest = Formatter::isHexDigit;
         c = r.read();
      } else {
         r.unread(c);
         digitTest = Formatter::isDigit;
      }

      boolean anyDigits = false;
      while (digitTest.test(c)) {
         anyDigits = true;
         b.append(c);
         c = r.read();
      }

      if (!anyDigits) {
         throw new IllegalStateException("Expected digits: " + c);
      }

      if (c != ';') {
         throw new IllegalStateException("Expected end of reference: " + c);
      }

      return b.append(';').toString();
   }

   private static boolean isDigit(final int c) {
      return c >= '0' && c <= '9';
   }

   private static boolean isHexDigit(final int c) {
      return isDigit(c) || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
   }

   ////////////////////////////////////////////////////////////////
   /////////////////////////// PARSING ////////////////////////////
   ////////////////////////////////////////////////////////////////

   private String parseName() throws IOException {
      // https://www.w3.org/TR/xml11/#NT-Name

      final StringBuilder b = new StringBuilder();

      int c = r.read();
      if (!isNameStartChar(c)) {
         throw new IllegalStateException("Expected name start character: " + c);
      }

      do {
         b.append((char) c);
         c = r.read();
      } while (isNameChar(c));

      if (c != -1) {
         r.unread(c);
      }

      return b.toString();
   }

   private static boolean isNameStartChar(final int c) {
      return c == ':' ||
             c >= 'A' && c <= 'Z' ||
             c == '_' ||
             c >= 'a' && c <= 'z' ||
             c >= 0xC0 && c <= 0xD6 ||
             c >= 0xD8 && c <= 0xF6 ||
             c >= 0xF8 && c <= 0x2FF ||
             c >= 0x370 && c <= 0x37D ||
             c >= 0x37F && c <= 0x1FFF ||
             c >= 0x200C && c <= 0x200D ||
             c >= 0x2070 && c <= 0x218F ||
             c >= 0x2C00 && c <= 0x2FEF ||
             c >= 0x3001 && c <= 0xDFFF || // assume all surrogates are for code points in [0x10000,0xEFFFF]
             c >= 0xF900 && c <= 0xFDCF ||
             c >= 0xFDF0 && c <= 0xFFFD;
   }

   private static boolean isNameChar(final int c) {
      return isNameStartChar(c) ||
             c == '-' ||
             c == '.' ||
             isDigit(c) ||
             c == 0xB7 ||
             c >= 0x0300 && c <= 0x036F ||
             c >= 0x203F && c <= 0x2040;
   }

   private String parseWhitespace() throws IOException {
      // https://www.w3.org/TR/xml11/#NT-S

      final StringBuilder b = new StringBuilder();

      int c;
      while (isWhitespace(c = r.read())) {
         b.append((char) c);
      }

      if (c != -1) {
         r.unread(c);
      }

      return b.length() == 0 ? null : b.toString();
   }

   private static boolean isWhitespace(final int c) {
      switch (c) {
         case 0x20:
         case 0x9:
         case 0xD:
         case 0xA:
            return true;
         default:
            return false;
      }
   }

   private void parseTag() throws IOException {
      // precondition: already read: "<"

      final int c = r.read();
      if (c == -1) {
         throw new IllegalStateException("Unclosed tag");
      }

      if (c == '?') {
         parseQuestionTag();
         return;
      }

      if (c == '!') {
         parseExclamationTag();
         return;
      }

      if (c == '/') {
         parseEndTag();
         return;
      }

      r.unread(c);
      parseStartTag();
   }

   private void parseStartTag() throws IOException {
      // precondition: already read: "<"
      // EmptyElemTag - https://www.w3.org/TR/xml11/#NT-EmptyElemTag <xxx ... />
      // STag - https://www.w3.org/TR/xml11/#NT-STag <xxx ... >

      final String tagName = parseName();

      final Map<String, String> attributes = new LinkedHashMap<>();

      int c;
      while (true) {
         parseWhitespace();

         c = r.read();

         if (c == '/') {
            c = r.read();
            if (c != '>') {
               throw new IllegalStateException("Expected '>': " + c);
            }

            writeEmptyTag(tagName, attributes);
            return;
         }

         if (c == '>') {
            writeStartTag(tagName, attributes);
            return;
         }

         r.unread(c);

         final String key = parseName();

         parseWhitespace();

         c = r.read();
         if (c != '=') {
            throw new IllegalStateException("Expected '=': " + c);
         }

         parseWhitespace();

         final int quote = r.read();
         if (quote != '"' && quote != '\'') {
            throw new IllegalStateException("Expected '\"' or '\\'': " + quote);
         }

         final String value = parseCharData(String.valueOf((char) quote));

         attributes.put(key, ((char) quote) + value + ((char) quote));
      }
   }

   private void parseEndTag() throws IOException {
      // precondition: already read: "</"
      // https://www.w3.org/TR/xml11/#NT-ETag

      final String tagName = parseName();

      parseWhitespace();

      final int c = r.read();
      if (c != '>') {
         throw new IllegalStateException("Expected '>': " + c);
      }

      writeEndTag(tagName);
   }

   private void parseQuestionTag() throws IOException {
      // precondition: already read: "<?"

      // XMLDecl - https://www.w3.org/TR/xml11/#NT-XMLDecl - <?xml ... ?>
      // PI - https://www.w3.org/TR/xml11/#NT-PI - <? ... ?>

      throw new UnsupportedOperationException("TODO");
   }

   private void parseExclamationTag() throws IOException {
      // precondition: already read: "<!"

      // doctypedecl - https://www.w3.org/TR/xml11/#NT-doctypedecl - <!DOCTYPE ... >
      // CDSect - https://www.w3.org/TR/xml11/#NT-CDSect - <![CDATA[ ... ]]>
      // Comment - https://www.w3.org/TR/xml11/#NT-Comment <!-- ... -->

      throw new UnsupportedOperationException("TODO");
   }

   ////////////////////////////////////////////////////////////////
   /////////////////////////// WRITING ////////////////////////////
   ////////////////////////////////////////////////////////////////

   enum OneLineStatus {
      /** We know this element can fit on one line, using spaces between attributes. */
      ONE_LINE,

      /** This element can fit on one line if there are any child elements. If it is empty, we need 2 extra characters to add " /" before the ">". */
      ONE_LINE_UNLESS_EMPTY,

      /** We know this element cannot fit on one line, so put all attributes on their own lines. */
      MULTIPLE_LINES
   }

   private int indent = 0;

   /**
    * If null: a start tag is not in progress
    * Non-null: the in-progress start tag's 'oneLine' status
    */
   private OneLineStatus startTagOneLine = null;

   /**
    * Attributes of the start tag, if startTagOneLine=ONE_LINE_UNLESS_EMPTY. We can't write them until we know what the format is.
    */
   private Map<String, String> inProgressAttributes = null;

   /**
    * If null: a tag is not in progress (or the in-progress tag is not one-line)
    * Non-null: the in-progress tag's maximum length of a child string for it to still fit on one line
    */
   private Integer maxChildStringOneLineLength = null;

   /**
    * (see maxChildStringOneLineLength)
    * The string that will be used as the only child of this element, if it is the only child
    */
   private String childStringOneLine = null;

   private void writeIndent() throws IOException {
      w.write(o.useTabs ? "\t".repeat(indent) : " ".repeat(indent * o.tabWidth));
   }

   private void writeEmptyTag(final String tagName, final Map<String, String> attributes) throws IOException {
      log("writeEmptyTag");
      log("- tagName: " + tagName);
      log("- attributes:");
      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         log("  - " + e.getKey() + "=" + e.getValue());
      }

      finishInProgressStuff();

      final boolean oneLine;
      if (o.singleAttributePerLine) {
         oneLine = false;
      } else {
         int lineLength = indent * o.tabWidth + 1 + tagName.length();
         for (final Map.Entry<String, String> e : attributes.entrySet()) {
            lineLength += 1 + e.getKey().length() + 1 + e.getValue().length();
         }
         lineLength += 3;
         log("- lineLength: " + lineLength);

         oneLine = lineLength <= o.printWidth;
      }
      log("- oneLine: " + oneLine);

      writeTagStart(tagName, attributes, oneLine ? OneLineStatus.ONE_LINE : OneLineStatus.MULTIPLE_LINES);

      w.write(oneLine || o.bracketSameLine ? " />" : "/>");
      w.write(o.endOfLine.string);
   }

   private void writeStartTag(final String tagName, final Map<String, String> attributes) throws IOException {
      log("writeStartTag");
      log("- tagName: " + tagName);
      log("- attributes:");
      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         log("  - " + e.getKey() + "=" + e.getValue());
      }

      finishInProgressStuff();

      int lineLength = indent * o.tabWidth + 1 + tagName.length();
      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         lineLength += 1 + e.getKey().length() + 1 + e.getValue().length();
      }
      log("- lineLength: " + lineLength);

      final OneLineStatus oneLine;
      if (attributes.isEmpty() || lineLength + 3 <= o.printWidth) {
         oneLine = OneLineStatus.ONE_LINE;
      } else if (o.singleAttributePerLine || lineLength + 1 > o.printWidth) {
         oneLine = OneLineStatus.MULTIPLE_LINES;
      } else {
         oneLine = OneLineStatus.ONE_LINE_UNLESS_EMPTY;
      }
      log("- oneLine: " + oneLine);

      writeTagStart(tagName, attributes, oneLine);

      startTagOneLine = oneLine;

      if (oneLine == OneLineStatus.ONE_LINE) {
         lineLength += 1 + 2 + tagName.length() + 1;

         maxChildStringOneLineLength = o.printWidth - lineLength;
         if (maxChildStringOneLineLength <= 0) {
            maxChildStringOneLineLength = null;
         }
      }
   }

   private void writeTagStart(final String tagName, final Map<String, String> attributes, final OneLineStatus oneLine) throws IOException {
      log("  writeTagStart");
      log("  - tagName: " + tagName);
      log("  - attributes:");
      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         log("    - " + e.getKey() + "=" + e.getValue());
      }
      log("  - oneLine: " + oneLine);

      writeIndent();

      w.write('<');
      w.write(tagName);

      if (attributes.isEmpty()) {
         log("  - empty attributes");
         return;
      }

      switch (oneLine) {
         case ONE_LINE:
            writeAttributes(attributes, true);
            break;
         case MULTIPLE_LINES:
            writeAttributes(attributes, false);
            break;
         case ONE_LINE_UNLESS_EMPTY:
            inProgressAttributes = attributes;
            break;
         default:
            throw new IllegalArgumentException("Unexpected OneLineStatus: " + oneLine);
      }
   }

   private void writeAttributes(final Map<String, String> attributes, final boolean oneLine) throws IOException {
      log("    writeAttributes");
      log("    - attributes:");
      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         log("      - " + e.getKey() + "=" + e.getValue());
      }
      log("    - oneLine: " + oneLine);

      indent++;
      log("    - indent++: " + indent);

      for (final Map.Entry<String, String> e : attributes.entrySet()) {
         if (oneLine) {
            w.write(' ');
         } else {
            w.write(o.endOfLine.string);
            writeIndent();
         }

         w.write(e.getKey());
         w.write('=');
         w.write(e.getValue());
      }

      indent--;
      log("    - indent--: " + indent);

      if (!(oneLine || o.bracketSameLine)) {
         w.write(o.endOfLine.string);
         writeIndent();
      }
   }

   private void finishInProgressStuff() throws IOException {
      finishInProgressStuff(null);
   }

   private void finishInProgressStuff(final String endTag) throws IOException {
      log("  finishInProgressStuff");
      log("  - endTag: " + endTag);

      if (startTagOneLine == null) {
         log("  - no text to consider");
         if (endTag != null) {
            log("  - writing end tag");

            indent--;
            log("  - indent--: " + indent);
            writeIndent();

            w.write("</");
            w.write(endTag);
            w.write('>');

            w.write(o.endOfLine.string);
         }

         return;
      }

      final boolean emptyTag = childStringOneLine == null && endTag != null;
      log("  - emptyTag: " + emptyTag);

      final boolean oneLine;
      switch (startTagOneLine) {
         case ONE_LINE:
            oneLine = true;
            break;
         case MULTIPLE_LINES:
            oneLine = false;
            break;
         case ONE_LINE_UNLESS_EMPTY:
            oneLine = !emptyTag;
            writeAttributes(inProgressAttributes, oneLine);
            break;
         default:
            throw new IllegalArgumentException("Unexpected OneLineStatus: " + startTagOneLine);
      }
      log("  - oneLine: " + oneLine);

      w.write(!emptyTag ? ">" : oneLine ? " />" : "/>");

      if (endTag == null) {
         indent++;
         log("  - indent++: " + indent);
      }

      startTagOneLine = null;
      maxChildStringOneLineLength = null;

      if (childStringOneLine != null) {
         if (endTag == null) {
            w.write(o.endOfLine.string);
            writeIndent();
         }

         log("  - writing text: " + childStringOneLine);
         w.write(childStringOneLine);
         childStringOneLine = null;

         if (endTag != null) {
            indent--;
            log("  - indent--: " + indent);

            log("  - writing end tag");
            w.write("</");
            w.write(endTag);
            w.write('>');
         }
      }

      w.write(o.endOfLine.string);
   }

   private void writeEndTag(final String tagName) throws IOException {
      log("writeEndTag");
      log("- tagName: " + tagName);

      finishInProgressStuff(tagName);
   }

   private void writeNewLine() throws IOException {
      log("writeNewLine");

      finishInProgressStuff();

      w.write(o.endOfLine.string);
   }

   private void writeText(final String text) throws IOException {
      log("writeText");
      log("- text: " + stringify(text));

      if (
         maxChildStringOneLineLength != null &&
         text.length() <= maxChildStringOneLineLength &&
         !text.contains("\n")
      ) {
         log("- saving text for later");

         maxChildStringOneLineLength = null;
         childStringOneLine = text;
         return;
      }

      finishInProgressStuff();

      log("- writing text");
      writeIndent();
      w.write(text);

      w.write(o.endOfLine.string);
   }

   private static void log(final String message) {
      if (DEBUG) {
         System.out.println(message);
      }
   }

   private static String stringify(final String s) {
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

   public void printDebugInfo() {
      log("- indent: " + indent);
      log("- startTagOneLine: " + startTagOneLine);
      log("- inProgressAttributes:" + inProgressAttributes);
      log("- maxChildStringOneLineLength: " + maxChildStringOneLineLength);
      log("- childStringOneLine: " + childStringOneLine);
   }
}
