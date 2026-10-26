package com.traceability.app.application.media;

import com.traceability.app.infrastructure.media.InMemoryMediaStorageAdapter;
import com.traceability.contracts.media.MediaStorageUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.traceability.app.application.media.MediaPurpose.CAMPAIGN_COVER;
import static com.traceability.app.application.media.MediaPurpose.DELIVERY_EVIDENCE;
import static com.traceability.app.application.media.MediaPurpose.ORGANIZATION_LOGO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Subida de una imagen (ADR-051 F2, F4, F9) a nivel de servicio, con el adaptador en memoria y el puerto
 * {@link MediaObjectStore} simulado: definición de hecho 1, 2, 3, 5 y 15 (el 413/415/503 por HTTP es de la Tarea 2), orden de escritura y
 * deduplicación de la evidencia.
 */
@ExtendWith(OutputCaptureExtension.class)
class MediaUploadServiceTest {

    static final String ORG = "org-7f3a";
    static final String UPLOADER = "acct-uploader-91";
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

    private InMemoryMediaStorageAdapter storage;
    private MediaObjectStore metadata;
    private final List<MediaObject> inserted = new ArrayList<>();
    private MediaUploadService service;

    @BeforeEach
    void setUp() {
        storage = spy(new InMemoryMediaStorageAdapter());
        metadata = mock(MediaObjectStore.class);
        doAnswer(invocation -> inserted.add(invocation.getArgument(0))).when(metadata).insert(any(MediaObject.class));
        when(metadata.findByOrganizationIdAndPurposeAndSha256(any(), any(), any())).thenAnswer(invocation ->
                inserted.stream().filter(d -> d.organizationId().equals(invocation.getArgument(0))
                        && d.purpose() == invocation.getArgument(1)
                        && d.sha256().equals(invocation.getArgument(2))).findFirst());
        service = new MediaUploadService(new ImageSanitizer(), storage, metadata,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private byte[] stored(StoredMedia media) {
        return storage.get(media.objectKey()).orElseThrow();
    }

    // ── Definición de hecho 1: sin EXIF ni XMP, leyendo los bytes guardados ──

    @Test
    void jpegWithGpsExifXmpAndComment_storedBytesHaveNoApp1NorComment() {
        byte[] input = TestImages.withExifGpsXmpAndComment(TestImages.jpeg(64, 48));
        assertThat(TestImages.jpegMarkers(input)).contains(0xE1, 0xFE);
        assertThat(TestImages.contains(input, "GPS")).isTrue();

        byte[] stored = stored(service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, input));

        assertThat(TestImages.jpegMarkers(stored)).doesNotContain(0xE1, 0xFE).contains(0xDA);
        assertThat(TestImages.contains(stored, "Exif")).isFalse();
        assertThat(TestImages.contains(stored, "GPS")).isFalse();
        assertThat(TestImages.contains(stored, "http://ns.adobe.com/xap")).isFalse();
        assertThat(TestImages.contains(stored, TestImages.SECRET_MARK)).isFalse();
    }

    @Test
    void pngWithExifAndTextChunks_storedBytesHaveNoMetadataChunks() {
        byte[] input = TestImages.withPngMetadataChunks(TestImages.png(40, 30));
        assertThat(TestImages.pngChunkTypes(input)).contains("eXIf", "tEXt", "iTXt", "zTXt");

        byte[] stored = stored(service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, input));

        assertThat(TestImages.pngChunkTypes(stored)).doesNotContain("eXIf", "tEXt", "iTXt", "zTXt")
                .contains("IHDR", "IDAT", "IEND");
        assertThat(TestImages.contains(stored, TestImages.SECRET_MARK)).isFalse();
    }

    // ── Definición de hecho 2 (a nivel de servicio): la huella es la de los bytes guardados ──

    @Test
    void returnedSha256_isTheDigestOfTheStoredBytes_notOfTheUploadedOnes() {
        byte[] input = TestImages.withExifGpsXmpAndComment(TestImages.jpeg(64, 48));

        StoredMedia media = service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, input);

        assertThat(media.sha256()).isEqualTo(TestImages.sha256Hex(stored(media)))
                .isNotEqualTo(TestImages.sha256Hex(input));
        assertThat(media.sizeBytes()).isEqualTo(stored(media).length);
    }

    // ── Definición de hecho 3: formato no admitido, sin efectos ──

    @Test
    void unsupportedFormat_neverPutsNorInserts() {
        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, TestImages.gif(8, 8)))
                .isInstanceOf(UnsupportedImageFormatException.class);

        verify(storage, never()).put(any(), any(), any());
        verify(metadata, never()).insert(any(MediaObject.class));
    }

    // ── Definición de hecho 5: más de 5 MB de evidencia o 2 MB de logo o portada ──

    @ParameterizedTest
    @EnumSource(MediaPurpose.class)
    void overThePurposeSizeLimit_isTooLarge_withoutPutNorInsert(MediaPurpose purpose) {
        byte[] content = new byte[purpose.maxBytes() + 1];
        System.arraycopy(TestImages.jpeg(16, 16), 0, content, 0, 3);
        String ownerRef = purpose == CAMPAIGN_COVER ? "cmp-1" : null;

        assertThatThrownBy(() -> service.store(purpose, ORG, ownerRef, UPLOADER, content))
                .isInstanceOf(ImageTooLargeException.class);

        verify(storage, never()).put(any(), any(), any());
        verify(metadata, never()).insert(any(MediaObject.class));
    }

    @Test
    void theLimitsAre5MiBForEvidence_and2MiBForLogoAndCover() {
        assertThat(DELIVERY_EVIDENCE.maxBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(ORGANIZATION_LOGO.maxBytes()).isEqualTo(2 * 1024 * 1024);
        assertThat(CAMPAIGN_COVER.maxBytes()).isEqualTo(2 * 1024 * 1024);
    }

    // exactamente en el límite pasa la comprobación de tamaño: sin bytes de imagen, lo que falla es el formato
    @ParameterizedTest
    @EnumSource(MediaPurpose.class)
    void exactlyAtTheLimit_passesTheSizeCheck(MediaPurpose purpose) {
        String ownerRef = purpose == CAMPAIGN_COVER ? "cmp-1" : null;
        assertThatThrownBy(() -> service.store(purpose, ORG, ownerRef, UPLOADER, new byte[purpose.maxBytes()]))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    // ── Definición de hecho 15 (a nivel de servicio): el proveedor falla → ningún metadato ──

    @Test
    void storageUnavailable_propagates_andNoMetadataIsInserted() {
        storage.failNextPuts(1);

        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, TestImages.jpeg(32, 32)))
                .isInstanceOf(MediaStorageUnavailableException.class);

        verify(metadata, never()).insert(any(MediaObject.class));
        assertThat(inserted).isEmpty();
    }

    // ── ADR-051 F2: limpiar → put con ULID → insertar ──

    @Test
    void writeOrder_isCleanThenPutThenInsert_withTheExactMetadata() {
        StoredMedia media = service.store(CAMPAIGN_COVER, ORG, "cmp-42", UPLOADER, TestImages.png(30, 20));

        InOrder order = inOrder(storage, metadata);
        order.verify(storage).put(eq(media.objectKey()), any(), eq("image/png"));
        ArgumentCaptor<MediaObject> captor = ArgumentCaptor.forClass(MediaObject.class);
        order.verify(metadata).insert(captor.capture());

        MediaObject document = captor.getValue();
        assertThat(document.objectKey()).isEqualTo(media.objectKey()).matches(ULID);
        assertThat(document.sha256()).isEqualTo(media.sha256()).isEqualTo(TestImages.sha256Hex(stored(media)));
        assertThat(document.purpose()).isEqualTo(CAMPAIGN_COVER);
        assertThat(document.organizationId()).isEqualTo(ORG);
        assertThat(document.ownerRef()).isEqualTo("cmp-42");
        assertThat(document.contentType()).isEqualTo("image/png");
        assertThat(document.sizeBytes()).isEqualTo(stored(media).length);
        assertThat(new int[]{document.width(), document.height()}).containsExactly(30, 20);
        assertThat(document.uploadedBy()).isEqualTo(UPLOADER);
        assertThat(document.uploadedAt()).isEqualTo(NOW);
        assertThat(storage.contentTypeOf(media.objectKey())).isEqualTo("image/png");
    }

    // ADR-051 F2.2: clave aleatoria (ULID), sin datos de la organización
    @Test
    void objectKey_isARandomUlid_withoutOrganizationData() {
        StoredMedia first = service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, TestImages.png(10, 10));
        StoredMedia second = service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, TestImages.png(10, 10));

        assertThat(first.objectKey()).matches(ULID).doesNotContain(ORG);
        assertThat(second.objectKey()).matches(ULID).isNotEqualTo(first.objectKey());
    }

    // ADR-051 F2: si falla el insert queda un huérfano, que se registra con su clave (y nada más)
    @Test
    void insertFailure_logsTheOrphanKey_withoutMessageNorOrganizationNorUploader(CapturedOutput output) {
        doThrow(new DataAccessResourceFailureException("mongo down " + ORG)).when(metadata).insert(any(MediaObject.class));

        assertThatThrownBy(() -> service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, TestImages.png(10, 10)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(storage.keys()).hasSize(1);
        String orphan = storage.keys().iterator().next();
        assertThat(output.getAll())
                .contains("media.orphan objectKey=" + orphan + " purpose=ORGANIZATION_LOGO cause=DataAccessResourceFailureException")
                .doesNotContain("mongo down").doesNotContain(ORG).doesNotContain(UPLOADER);
    }

    // ── ADR-051 F9: misma evidencia dos veces → el mismo ref, sin duplicar ──

    @Test
    void sameEvidenceBytesTwice_returnsTheSameRef_withoutASecondPut() {
        byte[] photo = TestImages.jpeg(32, 32);

        StoredMedia first = service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, photo);
        StoredMedia second = service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, photo);

        assertThat(second).isEqualTo(first);
        assertThat(storage.keys()).containsExactly(first.objectKey());
        assertThat(inserted).hasSize(1);
    }

    @Test
    void deduplication_isOnlyForEvidence_aRepeatedLogoIsStoredAgain() {
        byte[] logo = TestImages.png(10, 10);

        StoredMedia first = service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, logo);
        StoredMedia second = service.store(ORGANIZATION_LOGO, ORG, null, UPLOADER, logo);

        assertThat(second.objectKey()).isNotEqualTo(first.objectKey());
        assertThat(inserted).hasSize(2);
        verify(metadata, never()).findByOrganizationIdAndPurposeAndSha256(any(), eq(ORGANIZATION_LOGO), any());
    }

    // dos subidas simultáneas de la misma evidencia: gana el índice único; el perdedor devuelve la del ganador y su
    // objeto queda huérfano, registrado y no borrado (P-8)
    @Test
    void concurrentDuplicateEvidence_returnsTheWinner_andLogsTheOrphan(CapturedOutput output) {
        byte[] photo = TestImages.jpeg(32, 32);
        String sha = new ImageSanitizer().sanitize(DELIVERY_EVIDENCE, photo).sha256();
        MediaObject winner = new MediaObject("01JB00000000000000000000ZZ", sha, DELIVERY_EVIDENCE, ORG, null,
                "image/jpeg", 100, 32, 32, "acct-other", NOW);
        when(metadata.findByOrganizationIdAndPurposeAndSha256(ORG, DELIVERY_EVIDENCE, sha))
                .thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateEvidenceException("uq_evidence_sha256")).when(metadata).insert(any(MediaObject.class));

        StoredMedia media = service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, photo);

        assertThat(media.objectKey()).isEqualTo(winner.objectKey());
        assertThat(media.sha256()).isEqualTo(sha);
        String orphan = storage.keys().iterator().next();
        assertThat(orphan).isNotEqualTo(winner.objectKey());
        verify(storage, never()).delete(any());
        assertThat(output.getAll()).contains("media.orphan objectKey=" + orphan + " purpose=DELIVERY_EVIDENCE");
    }

    // ── Deuda 1 (plan de las deudas 1 a 3 §1, P-DB1-1): los bytes de una evidencia retirada por F7 se rechazan ──

    @Test
    void db1_theBytesOfARemovedEvidence_areRejected_withoutPutNorInsert() {
        byte[] photo = TestImages.jpeg(32, 32);
        String sha = new ImageSanitizer().sanitize(DELIVERY_EVIDENCE, photo).sha256();
        inserted.add(new MediaObject("01JB00000000000000000000RM", sha, DELIVERY_EVIDENCE, ORG, null, "image/jpeg", 100,
                32, 32, "acct-other", NOW, NOW.plusSeconds(60)));

        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, photo))
                .isInstanceOf(EvidenceRemovedException.class);

        verify(storage, never()).put(any(), any(), any());
        verify(metadata, never()).insert(any(MediaObject.class));
        assertThat(storage.keys()).isEmpty();
    }

    // P-DB1-2: la carrera, cuando el ganador que se lee ya está retirado
    @Test
    void db1_aRaceWhoseWinnerIsRemoved_isRejectedToo() {
        byte[] photo = TestImages.jpeg(32, 32);
        String sha = new ImageSanitizer().sanitize(DELIVERY_EVIDENCE, photo).sha256();
        MediaObject removedWinner = new MediaObject("01JB00000000000000000000RW", sha, DELIVERY_EVIDENCE, ORG, null,
                "image/jpeg", 100, 32, 32, "acct-other", NOW, NOW.plusSeconds(60));
        when(metadata.findByOrganizationIdAndPurposeAndSha256(ORG, DELIVERY_EVIDENCE, sha))
                .thenReturn(Optional.empty(), Optional.of(removedWinner));
        doThrow(new DuplicateEvidenceException("uq_evidence_sha256")).when(metadata).insert(any(MediaObject.class));

        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, ORG, null, UPLOADER, photo))
                .isInstanceOf(EvidenceRemovedException.class);
    }

    // ADR-051 F9: ownerRef es el campaignRef de la portada y nulo en los demás (P-5: IllegalArgumentException)
    @Test
    void ownerRef_isRequiredForTheCover_andForbiddenOtherwise() {
        byte[] png = TestImages.png(10, 10);
        assertThatThrownBy(() -> service.store(CAMPAIGN_COVER, ORG, null, UPLOADER, png))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.store(ORGANIZATION_LOGO, ORG, "cmp-1", UPLOADER, png))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.store(DELIVERY_EVIDENCE, ORG, "cmp-1", UPLOADER, png))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storage, never()).put(any(), any(), any());
    }
}
