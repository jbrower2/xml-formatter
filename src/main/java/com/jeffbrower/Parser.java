package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.Closeable;
import java.io.IOException;
import java.io.PushbackReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;

public class Parser implements Closeable {
   private final PushbackReader r;
   private final Formatter f;

   public Parser(final PushbackReader r, final Formatter f) {
      this.r = r;
      this.f = f;
   }

   @Override
   public void close() throws IOException {
      r.close();
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
            f.writeNewLine();
         } else {
            log("empty char data: " + stringify(rawCharData));
         }
         return true;
      }

      final String whitespacePrefix = rawCharData.substring(0, rawCharData.length() - charDataTrimStart.length());
      if (hasMultipleNewlines(whitespacePrefix)) {
         f.writeNewLine();
      }

      final String charData = charDataTrimStart.stripTrailing();
      f.writeText(charData);

      final String whitespaceSuffix = charDataTrimStart.substring(charData.length());
      if (hasMultipleNewlines(whitespaceSuffix)) {
         f.writeNewLine();
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
         digitTest = Parser::isHexDigit;
         c = r.read();
      } else {
         r.unread(c);
         digitTest = Parser::isDigit;
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

            f.writeEmptyTag(tagName, attributes);
            return;
         }

         if (c == '>') {
            f.writeStartTag(tagName, attributes);
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

      f.writeEndTag(tagName);
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
}
