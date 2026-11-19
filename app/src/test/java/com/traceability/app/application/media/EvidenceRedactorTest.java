package com.traceability.app.application.media;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-051 Enmienda 2, E2-4 y Tarea 3 (plan §3, grupo A): la copia censurada es un pixelado de 24 bloques en el lado
 * largo, cada bloque de 20 × 20 px de un solo color, determinista y sin metadatos.
 */
class EvidenceRedactorTest {

    private final EvidenceRedactor redactor = new EvidenceRedactor();

    private static byte[] png(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** Rayas verticales de 1 px alternando blanco y negro: el patrón más fino que existe. */
    private static BufferedImage stripes(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, x % 2 == 0 ? 0xFFFFFF : 0x000000);
            }
        }
        return image;
    }

    private static BufferedImage gradient(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                image.setRGB(x, y, new Color((x * 255) / Math.max(1, w - 1), (y * 255) / Math.max(1, h - 1), (x + y) % 256).getRGB());
            }
        }
        return image;
    }

    // test 8 (E2-4): cada bloque de 20 × 20 px es de un solo color; las rayas de 1 px no aparecen
    @Test
    void test8_everyBlockIs20x20OfASingleColour_andTheOnePixelStripesDoNotAppear() throws Exception {
        var result = redactor.redact(png(stripes(800, 600)));
        BufferedImage out = decode(result.png());

        assertThat(out.getWidth()).isEqualTo(480);
        assertThat(out.getHeight() % 20).isZero();
        int blocksX = out.getWidth() / 20, blocksY = out.getHeight() / 20;
        assertThat(blocksX).isEqualTo(24);
        for (int bx = 0; bx < blocksX; bx++) {
            for (int by = 0; by < blocksY; by++) {
                int colour = out.getRGB(bx * 20, by * 20);
                for (int x = 0; x < 20; x++) {
                    for (int y = 0; y < 20; y++) {
                        // ninguna transición dentro de un bloque
                        assertThat(out.getRGB(bx * 20 + x, by * 20 + y)).as("bloque " + bx + "," + by).isEqualTo(colour);
                    }
                }
            }
        }
        // y de las rayas no queda blanco ni negro puro: son un gris medio (el promedio), nunca el patrón
        int sample = out.getRGB(10, 10) & 0xFFFFFF;
        assertThat(sample).isNotEqualTo(0xFFFFFF).isNotEqualTo(0x000000);
    }

    // geometría: 24 en el lado largo y N = round(24 × corto / largo) (mínimo 1) en el corto
    @Test
    void theGeometry_is24OnTheLongSide_andRoundedOnTheShortOne_withAMinimumOfOne() throws Exception {
        int[][] cases = {
                {800, 600, 480, 360},   // apaisada: N = round(24·600/800) = 18
                {600, 800, 360, 480},   // vertical
                {100, 100, 480, 480},   // cuadrada
                {2400, 100, 480, 20},   // panorámica: N = 1
                {5000, 10, 480, 20}};   // extrema: round(0,048) = 0 → mínimo 1
        for (int[] c : cases) {
            var result = redactor.redact(png(gradient(c[0], c[1])));
            BufferedImage out = decode(result.png());
            assertThat(new int[] {out.getWidth(), out.getHeight()}).as(c[0] + "x" + c[1]).containsExactly(c[2], c[3]);
            assertThat(new int[] {result.width(), result.height()}).containsExactly(c[2], c[3]);
        }
    }

    // el color de cada bloque es el promedio de su zona: mitad roja y mitad azul se conservan en su sitio
    @Test
    void theColourOfEachBlock_isTheAverageOfItsArea() throws Exception {
        BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 48; x++) for (int y = 0; y < 48; y++) image.setRGB(x, y, x < 24 ? 0xFF0000 : 0x0000FF);

        BufferedImage out = decode(redactor.redact(png(image)).png());

        assertThat(out.getRGB(5, 5) & 0xFFFFFF).isEqualTo(0xFF0000);          // un bloque de la izquierda
        assertThat(out.getRGB(480 - 5, 5) & 0xFFFFFF).isEqualTo(0x0000FF);    // uno de la derecha
    }

    // test 7b: determinista (los mismos bytes dan los mismos bytes), también con transparencia compuesta sobre blanco
    @Test
    void test7b_isDeterministic_andTransparencyIsFlattenedOverWhite() throws Exception {
        byte[] input = png(gradient(640, 480));
        assertThat(redactor.redact(input).png()).isEqualTo(redactor.redact(input).png());

        BufferedImage transparent = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB); // todo transparente
        byte[] a = redactor.redact(png(transparent)).png();
        assertThat(redactor.redact(png(transparent)).png()).isEqualTo(a);
        BufferedImage out = decode(a);
        assertThat(out.getRGB(30, 30) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    // sin metadatos: ni EXIF, ni texto, ni perfil, ni fecha
    @Test
    void theOutput_isAPng_withoutMetadataChunks() throws Exception {
        byte[] out = redactor.redact(png(gradient(320, 240))).png();

        assertThat(Arrays.copyOf(out, 8)).containsExactly(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        String raw = new String(out, StandardCharsets.ISO_8859_1);
        for (String chunk : new String[] {"tEXt", "iTXt", "zTXt", "eXIf", "iCCP", "tIME", "pHYs"}) {
            assertThat(raw).as(chunk).doesNotContain(chunk);
        }
    }

    @Test
    void aJpegInput_isAlsoAccepted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(gradient(200, 100), "jpg", out);

        var result = redactor.redact(out.toByteArray());

        assertThat(result.width()).isEqualTo(480);
        assertThat(result.height()).isEqualTo(240); // N = round(24·100/200) = 12
    }
}
