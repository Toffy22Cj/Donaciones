package com.traceability.contracts;

public record SequenceRange(long fromSequence, long toSequence) {
    public SequenceRange {
        if (fromSequence > toSequence) {
            throw new IllegalArgumentException("fromSequence cannot be greater than toSequence");
        }
    }
}
