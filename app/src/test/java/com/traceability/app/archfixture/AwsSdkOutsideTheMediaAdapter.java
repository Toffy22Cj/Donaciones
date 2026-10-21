package com.traceability.app.archfixture;

import software.amazon.awssdk.services.s3.S3Client;

/** Violación deliberada para {@code MediaArchitectureTest}: el SDK de AWS fuera de {@code app.infrastructure.media}. */
public class AwsSdkOutsideTheMediaAdapter {

    S3Client client;
}
