package com.traceability.crypto.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.crypto.domain.MerkleTree.ProofStep;
import com.traceability.crypto.domain.MerkleTree.Side;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerkleTreeTest {

    @Test
    void testBuild_SingleLeaf_RootIsLeaf() {
        String leaf = "hash1";
        MerkleTree tree = MerkleTree.build(List.of(leaf));
        
        assertEquals(leaf, tree.getRoot(), "A single leaf tree should have the leaf itself as the root");
    }

    @Test
    void testBuild_EvenNumberOfLeaves() {
        List<String> leaves = List.of("hash1", "hash2", "hash3", "hash4");
        MerkleTree tree = MerkleTree.build(leaves);
        
        assertNotNull(tree.getRoot());
        // Since we don't expose the intermediate nodes, we just verify it computes successfully and deterministically
        MerkleTree tree2 = MerkleTree.build(leaves);
        assertEquals(tree.getRoot(), tree2.getRoot());
    }

    @Test
    void testBuild_OddNumberOfLeaves_DuplicatesLast() {
        List<String> oddLeaves = List.of("hash1", "hash2", "hash3");
        MerkleTree oddTree = MerkleTree.build(oddLeaves);
        
        List<String> evenLeaves = List.of("hash1", "hash2", "hash3", "hash3");
        MerkleTree evenTree = MerkleTree.build(evenLeaves);
        
        assertEquals(oddTree.getRoot(), evenTree.getRoot(), "An odd number of leaves should duplicate the last leaf for hashing");
    }
    
    @Test
    void testBuild_EmptyList_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> MerkleTree.build(List.of()));
    }

    // ---- MerkleTree.proof (ficha R-6, Enmienda 1, E1.3 y E1.5; plan del Bloque C, versión 2, tests C-E1 a C-E4) ----
    // El verificador es el pseudocódigo de E1.3, escrito aquí sin usar MerkleTree.

    private static final String VECTORS = "/merkle/proof-vectors.json";

    /** E1.3: nodo = eventHash; LEFT → sha256(paso + nodo); RIGHT → sha256(nodo + paso); válido ⇔ nodo == raíz. */
    private static boolean verify(String leaf, List<ProofStep> path, String root) {
        String node = leaf;
        for (ProofStep step : path) {
            node = step.side() == Side.LEFT ? sha256Hex(step.hash() + node) : sha256Hex(node + step.hash());
        }
        return node.equals(root);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> leaves(int n) {
        return IntStream.range(0, n).mapToObj(i -> sha256Hex("leaf-" + i)).toList();
    }

    // C-E1: de 1 a 33 hojas, la prueba de cada índice reconstruye getRoot()
    @Test
    void proof_everyIndexOfEveryTreeFrom1To33Leaves_rebuildsTheRoot() {
        for (int n = 1; n <= 33; n++) {
            List<String> leaves = leaves(n);
            String root = MerkleTree.build(leaves).getRoot();
            for (int i = 0; i < n; i++) {
                List<ProofStep> path = MerkleTree.proof(leaves, i);
                assertThat(verify(leaves.get(i), path, root)).as("%d hojas, índice %d", n, i).isTrue();
                if (n > 1) {
                    // El mismo camino con otra hoja no verifica
                    assertThat(verify(leaves.get((i + 1) % n), path, root)).as("%d hojas, índice %d, otra hoja", n, i)
                            .isFalse();
                }
            }
        }
    }

    // C-E1: un paso por nivel
    @Test
    void proof_hasOneStepPerLevel() {
        assertThat(MerkleTree.proof(leaves(1), 0)).isEmpty();
        assertThat(MerkleTree.proof(leaves(2), 1)).hasSize(1);
        assertThat(MerkleTree.proof(leaves(5), 4)).hasSize(3);
        assertThat(MerkleTree.proof(leaves(33), 32)).hasSize(6);
    }

    // Regla del nodo impar (MerkleTree.java, build): el último nodo de un nivel impar da {RIGHT, el mismo nodo}
    @Test
    void proof_theLastNodeOfAnOddLevel_isPairedWithItselfOnTheRight() {
        List<String> leaves = leaves(3);
        List<ProofStep> path = MerkleTree.proof(leaves, 2);
        assertThat(path).containsExactly(
                new ProofStep(Side.RIGHT, leaves.get(2)),
                new ProofStep(Side.LEFT, sha256Hex(leaves.get(0) + leaves.get(1))));
    }

    // C-E2: cambiar un carácter de la hoja o de un paso, o el side de un paso, invalida la prueba
    @Test
    void proof_changingTheLeafAStepHashOrASide_invalidatesIt() {
        List<String> leaves = leaves(7);
        String root = MerkleTree.build(leaves).getRoot();
        for (int i = 0; i < leaves.size(); i++) {
            List<ProofStep> path = MerkleTree.proof(leaves, i);
            assertThat(verify(flip(leaves.get(i)), path, root)).as("hoja alterada, índice %d", i).isFalse();
            for (int s = 0; s < path.size(); s++) {
                List<ProofStep> hashChanged = new ArrayList<>(path);
                hashChanged.set(s, new ProofStep(path.get(s).side(), flip(path.get(s).hash())));
                assertThat(verify(leaves.get(i), hashChanged, root)).as("paso %d alterado, índice %d", s, i).isFalse();

                List<ProofStep> sideChanged = new ArrayList<>(path);
                Side other = path.get(s).side() == Side.LEFT ? Side.RIGHT : Side.LEFT;
                sideChanged.set(s, new ProofStep(other, path.get(s).hash()));
                // Solo un nodo emparejado consigo mismo (sha256(x + x)) tolera el cambio de lado
                assertThat(verify(leaves.get(i), sideChanged, root)).as("lado del paso %d cambiado, índice %d", s, i)
                        .isEqualTo(isSelfPairedStep(leaves.size(), i, s));
            }
        }
    }

    // C-E3: vectores fijos (crypto/src/test/resources/merkle/proof-vectors.json), los que copia la web para F-C2
    @Test
    void proof_fixedVectors_buildGivesTheRootAndProofGivesThePath() throws Exception {
        JsonNode vectors;
        try (InputStream in = getClass().getResourceAsStream(VECTORS)) {
            assertThat(in).as(VECTORS).isNotNull();
            vectors = new ObjectMapper().readTree(in);
        }
        boolean fiveLeavesIndex4 = false;
        boolean fiveLeavesIndex2 = false;
        for (JsonNode v : vectors) {
            String description = v.get("description").asText();
            List<String> leaves = new ArrayList<>();
            v.get("leaves").forEach(n -> leaves.add(n.asText()));
            int index = v.get("index").asInt();
            List<ProofStep> expected = new ArrayList<>();
            v.get("path").forEach(n -> expected.add(new ProofStep(Side.valueOf(n.get("side").asText()),
                    n.get("hash").asText())));
            String root = v.get("root").asText();

            // Las hojas son SHA-256 de "leaf-0", "leaf-1"…, para que la web pueda regenerarlas
            assertThat(leaves).as(description).isEqualTo(leaves(leaves.size()));
            assertThat(MerkleTree.build(leaves).getRoot()).as(description).isEqualTo(root);
            assertThat(MerkleTree.proof(leaves, index)).as(description).isEqualTo(expected);
            assertThat(verify(leaves.get(index), expected, root)).as(description).isTrue();
            fiveLeavesIndex4 |= leaves.size() == 5 && index == 4;
            fiveLeavesIndex2 |= leaves.size() == 5 && index == 2;
        }
        assertThat(fiveLeavesIndex4).as("vector de 5 hojas con índice 4").isTrue();
        assertThat(fiveLeavesIndex2).as("vector de 5 hojas con índice 2").isTrue();
    }

    // C-E3: con 5 hojas, la del índice 4 se combina consigo misma en dos niveles
    @Test
    void proof_fiveLeavesIndex4_isSelfPairedInTwoLevels() {
        List<String> leaves = leaves(5);
        String level1 = sha256Hex(leaves.get(4) + leaves.get(4));
        String level2Left = sha256Hex(sha256Hex(leaves.get(0) + leaves.get(1)) + sha256Hex(leaves.get(2) + leaves.get(3)));
        assertThat(MerkleTree.proof(leaves, 4)).containsExactly(
                new ProofStep(Side.RIGHT, leaves.get(4)),
                new ProofStep(Side.RIGHT, level1),
                new ProofStep(Side.LEFT, level2Left));
    }

    // C-E4: hojas vacías o índice fuera de rango → IllegalArgumentException, como build
    @Test
    void proof_emptyLeavesOrIndexOutOfRange_throwsIllegalArgument() {
        assertThatThrownBy(() -> MerkleTree.proof(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.proof(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.proof(leaves(3), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MerkleTree.proof(leaves(3), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** ¿El paso {@code step} de la prueba del índice {@code index} empareja el nodo consigo mismo? */
    private static boolean isSelfPairedStep(int leafCount, int index, int step) {
        int size = leafCount;
        int idx = index;
        for (int s = 0; s < step; s++) {
            size = (size + 1) / 2;
            idx /= 2;
        }
        return idx % 2 == 0 && idx + 1 >= size;
    }

    private static String flip(String hex) {
        char last = hex.charAt(hex.length() - 1);
        return hex.substring(0, hex.length() - 1) + (last == '0' ? '1' : '0');
    }
}
