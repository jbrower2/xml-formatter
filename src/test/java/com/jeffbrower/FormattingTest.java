package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static com.jeffbrower.Logger.stringify;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FormattingTest {
   @ParameterizedTest
   @ValueSource(strings = {
      "test1",
      "test2",
      "test3",
      "test4",
      "test5",
      "test6",
      "test7",
   })
   void test(final String baseName) throws IOException {
      final String in, out;
      try (
         final InputStream isIn = ClassLoader.getSystemResourceAsStream(baseName + ".in.xml");
         final InputStream isOut = ClassLoader.getSystemResourceAsStream(baseName + ".out.xml")
      ) {
         in = new String(isIn.readAllBytes(), StandardCharsets.UTF_8);
         out = new String(isOut.readAllBytes(), StandardCharsets.UTF_8);
      }

      log("input: " + stringify(in));
      log("output: " + stringify(out));

      // verify formatting properly with 'in' -> 'out'
      testFormat(in, out, new FormatOptions());

      // verify check function returns false for mismatched input
      testCheck(in, new FormatOptions(), false);
      
      // verify stable by checking that 'out' -> 'out'
      testFormat(out, out, new FormatOptions());

      // verify check function returns true for matching input
      testCheck(out, new FormatOptions(), true);
   }

   private static void testFormat(final String in, final String out, final FormatOptions o) throws IOException {
      try (
         final StringWriter w = new StringWriter();
         final Formatter f = new Formatter(w, o);
         final Parser p = new Parser(new StringReader(in), f)
      ) {
         while (p.parseOneStep()) {
            // keep going

            log("=".repeat(100));
            log(w.toString() + "|");
            log("=".repeat(100));
            f.printDebugInfo();
            log("=".repeat(100));
         }

         assertEquals(out, w.toString());
      }
   }

   private static void testCheck(final String in, final FormatOptions o, final boolean expected) throws IOException {
      try (final StringReader r = new StringReader(in)) {
         assertEquals(expected, Checker.check(r, o));
      }
   }
}
