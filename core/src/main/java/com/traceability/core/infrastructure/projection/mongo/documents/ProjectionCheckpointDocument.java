package com.traceability.core.infrastructure.projection.mongo.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "projection_checkpoints")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectionCheckpointDocument {

    @Id
    private String id; // usually a constant like "donation_projection_stream"
    
    private String resumeToken;
    
}
