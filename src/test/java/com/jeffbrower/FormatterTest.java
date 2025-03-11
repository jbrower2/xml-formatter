package com.jeffbrower;

import static com.jeffbrower.Logger.log;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class FormatterTest {
   @ParameterizedTest
   @ValueSource(strings = {
      "test1",
      "test2",
      "test3",
      "test4",
      "test5",
      "test6",
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

      final FormatOptions o = new FormatOptions();

      try (
         final StringWriter w = new StringWriter();
         final Formatter f = new Formatter(w, o);
         final Parser p = new Parser(new PushbackReader(new StringReader(in)), f)
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
}
