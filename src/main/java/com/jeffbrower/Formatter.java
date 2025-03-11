package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

public class Formatter implements Closeable {
   private final Writer w;
   private final FormatOptions o;

   public Formatter(final Writer w, final FormatOptions o) {
      this.w = w;
      this.o = o;
   }

   @Override
   public void close() throws IOException {
      w.close();
   }

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

   public void writeIndent() throws IOException {
      w.write(o.useTabs ? "\t".repeat(indent) : " ".repeat(indent * o.tabWidth));
   }

   public void writeEmptyTag(final String tagName, final Map<String, String> attributes) throws IOException {
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

   public void writeStartTag(final String tagName, final Map<String, String> attributes) throws IOException {
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

   public void writeTagStart(final String tagName, final Map<String, String> attributes, final OneLineStatus oneLine) throws IOException {
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

   public void writeAttributes(final Map<String, String> attributes, final boolean oneLine) throws IOException {
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

   public void finishInProgressStuff() throws IOException {
      finishInProgressStuff(null);
   }

   public void finishInProgressStuff(final String endTag) throws IOException {
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

   public void writeEndTag(final String tagName) throws IOException {
      log("writeEndTag");
      log("- tagName: " + tagName);

      finishInProgressStuff(tagName);
   }

   public void writeNewLine() throws IOException {
      log("writeNewLine");

      finishInProgressStuff();

      w.write(o.endOfLine.string);
   }

   public void writeText(final String text) throws IOException {
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

   public void printDebugInfo() {
      log("- indent: " + indent);
      log("- startTagOneLine: " + startTagOneLine);
      log("- inProgressAttributes:" + inProgressAttributes);
      log("- maxChildStringOneLineLength: " + maxChildStringOneLineLength);
      log("- childStringOneLine: " + childStringOneLine);
   }
}
