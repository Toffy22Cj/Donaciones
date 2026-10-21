package com.traceability.app.application.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.traceability.app.application.media.MediaPurpose.DELIVERY_EVIDENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Limpieza con la JDK (ADR-051 F4): formato por bytes mágicos (definición de hecho 3), dimensiones desde la cabecera
 * antes de decodificar (4), re-codificación JPEG con calidad 0,9 y huella de los bytes resultantes.
 */
class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer();

    static Stream<Arguments> notJpegNorPng() {
        return Stream.of(
                Arguments.of("GIF", TestImages.gif(8, 8)),
                Arguments.of("texto con extensión .jpg", "esto no es una imagen".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("vacío", new byte[0]),
                Arguments.of("solo FF D8", new byte[]{(byte) 0xFF, (byte) 0xD8}),
                Arguments.of("firma PNG incompleta", Arrays.copyOf(TestImages.png(8, 8), 7)),
                Arguments.of("WebP", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
    }

    // ADR-051, definición de hecho 3: ni la extensión ni el Content-Type cuentan; el servicio ni siquiera los recibe
    @ParameterizedTest(name = "{0}")
    @MethodSource("notJpegNorPng")
    void withoutJpegOrPngMagicBytes_isUnsupported(String description, byte[] content) {
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, content))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void aBmpThatImageIoCouldRead_isUnsupported_becauseTheReaderIsChosenByMagicBytes() {
        byte[] bmp = TestImages.bmp(8, 8);
        assertThat(bmp[0]).isEqualTo((byte) 'B');
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, bmp))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    static Stream<Arguments> cmykAndYcckJpegs() {
        byte[] cmyk = TestImages.cmykJpeg(16, 16);
        return Stream.of(
                Arguments.of("CMYK", cmyk),
                Arguments.of("CMYK de Adobe (transform 0)", TestImages.withAdobeTransform(cmyk, 0)),
                Arguments.of("YCCK (transform 2)", TestImages.withAdobeTransform(cmyk, 2)));
    }

    // ADR-051 F4 ("por ejemplo, JPEG CMYK → 415"), aclarado en el plan §4.6: la JDK 21.0.12 los decodifica, así que se
    // rechazan por el espacio de color de la imagen decodificada (ni TYPE_RGB ni TYPE_GRAY)
    @ParameterizedTest(name = "{0}")
    @MethodSource("cmykAndYcckJpegs")
    void cmykAndYcckJpegs_areUnsupported(String description, byte[] jpeg) {
        assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, jpeg))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    static Stream<Arguments> admittedPngs() {
        return Stream.of(
                Arguments.of("RGBA (cuatro componentes, RGB)", TestImages.pngRgba(12, 9)),
                Arguments.of("gris con alfa", TestImages.pngGrayAlpha(12, 9)),
                Arguments.of("paleta", TestImages.pngPalette(12, 9)));
    }

    // la regla es por espacio de color, no por número de componentes: un PNG RGBA tiene cuatro y se admite
    @ParameterizedTest(name = "{0}")
    @MethodSource("admittedPngs")
    void rgbaGrayAlphaAndPalettePngs_areAdmitted(String description, byte[] png) throws Exception {
        SanitizedImage image = sanitizer.sanitize(DELIVERY_EVIDENCE, png);

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(new int[]{image.width(), image.height()}).containsExactly(12, 9);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.content()));
        assertThat(new int[]{decoded.getWidth(), decoded.getHeight()}).containsExactly(12, 9);
    }

    @Test
    void jpeg_isReencodedAsJpeg_andPng_asPng_keepingTheirDimensions() throws Exception {
        SanitizedImage jpeg = sanitizer.sanitize(DELIVERY_EVIDENCE, TestImages.jpeg(64, 48));
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.content()).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        assertThat(new int[]{jpeg.width(), jpeg.height()}).containsExactly(64, 48);

        SanitizedImage png = sanitizer.sanitize(DELIVERY_EVIDENCE, TestImages.png(30, 20));
        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png.content()));
        assertThat(new int[]{decoded.getWidth(), decoded.getHeight()}).containsExactly(30, 20);
    }

    // ADR-051 F4.3: "JPEG con calidad 0,9" — mismos bytes que el codificador de la JDK con 0,9 sobre la imagen decodificada
    @Test
    void jpeg_isReencodedWithQuality09() throws Exception {
        byte[] input = TestImages.jpeg(64, 48);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(input));
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(expected)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(decoded, null, null), param);
        } finally {
            writer.dispose();
        }
        assertThat(sanitizer.sanitize(DELIVERY_EVIDENCE, input).content()).isEqualTo(expected.toByteArray());
    }

    // ADR-051 F4.4: la huella es la de los bytes resultantes
    @Test
    void sha256_isTheDigestOfTheOutputBytes_inLowercaseHex() {
        SanitizedImage image = sanitizer.sanitize(DELIVERY_EVIDENCE, TestImages.jpeg(32, 32));
        assertThat(image.sha256()).matches("[0-9a-f]{64}").isEqualTo(TestImages.sha256Hex(image.content()));
    }

    // ADR-051, definición de hecho 4: imagen pequeña en bytes con dimensiones por encima del máximo → 413 sin decodificar.
    // El PNG es la prueba: sus datos no alcanzan para esas dimensiones, así que decodificarlo daría Unsupported, no
    // TooLarge (el control de abajo lo demuestra).
    @Test
    void hugeDeclaredDimensions_areRejectedFromTheHeader_beforeDecoding() {
        byte[] png = TestImages.png(16, 16);
        byte[] pngBomb = TestImages.withPngDimensions(png, 8001, 10);
        assertThat(pngBomb.length).isLessThan(1024);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, pngBomb))
                .isInstanceOf(ImageTooLargeException.class);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, TestImages.withPngDimensions(png, 10, 8001)))
                .isInstanceOf(ImageTooLargeException.class);
    }

    // JPEG: solo prueba que el límite se lee de su cabecera. No prueba "sin decodificar": la JDK decodifica con
    // tolerancia un JPEG con la cabecera cambiada, y uno cortado tras el SOF ni siquiera tiene cabecera legible (plan §5)
    @Test
    void jpegWithHugeDeclaredDimensions_isRejectedByItsHeader() {
        byte[] jpegBomb = TestImages.withJpegDimensions(TestImages.jpeg(16, 16), 8001, 10);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, jpegBomb))
                .isInstanceOf(ImageTooLargeException.class);
    }

    @Test
    void control_thePatchedPngWithinLimits_cannotBeDecoded() {
        byte[] patched = TestImages.withPngDimensions(TestImages.png(16, 16), 100, 10);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, patched))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @Test
    void over40Megapixels_withBothSidesUnder8000_isTooLarge() {
        byte[] bomb = TestImages.withPngDimensions(TestImages.png(16, 16), 7000, 6000);
        assertThatThrownBy(() -> sanitizer.sanitize(DELIVERY_EVIDENCE, bomb))
                .isInstanceOf(ImageTooLargeException.class);
    }

    // ADR-051 F4 y Q2: 8000 px por lado y 40 megapíxeles, bordes incluidos
    @Test
    void dimensionLimits_boundaries() {
        assertThatCode(() -> ImageSanitizer.checkDimensions(8000, 5000)).doesNotThrowAnyException();
        assertThatCode(() -> ImageSanitizer.checkDimensions(8000, 1)).doesNotThrowAnyException();
        assertThatCode(() -> ImageSanitizer.checkDimensions(1, 8000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ImageSanitizer.checkDimensions(8001, 1)).isInstanceOf(ImageTooLargeException.class);
        assertThatThrownBy(() -> ImageSanitizer.checkDimensions(1, 8001)).isInstanceOf(ImageTooLargeException.class);
        assertThatThrownBy(() -> ImageSanitizer.checkDimensions(8000, 5001)).isInstanceOf(ImageTooLargeException.class);
        assertThatThrownBy(() -> ImageSanitizer.checkDimensions(6325, 6325)).isInstanceOf(ImageTooLargeException.class);
    }
}
