package com.traceability.crypto.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
