package com.andrerinas.openheadunit.cluster

import com.google.protobuf.UnknownFieldSet

/**
 * MediaSinkService fields this app's generated protocol classes do not have: `display_id` (6) and
 * `display_type` (7, MAIN 0 / CLUSTER 1 / AUXILIARY 2), as in the Android Auto wire schema that
 * aa-proxy-rs uses to add cluster displays. A sink without them is the main display (id 0).
 */
internal object ClusterSinkFields {
    const val DISPLAY_ID_FIELD = 6
    const val DISPLAY_TYPE_FIELD = 7
    const val DISPLAY_TYPE_CLUSTER = 1L
    const val CLUSTER_DISPLAY_ID = 1L

    fun cluster(): UnknownFieldSet = UnknownFieldSet.newBuilder()
        .addField(DISPLAY_ID_FIELD, UnknownFieldSet.Field.newBuilder().addVarint(CLUSTER_DISPLAY_ID).build())
        .addField(DISPLAY_TYPE_FIELD, UnknownFieldSet.Field.newBuilder().addVarint(DISPLAY_TYPE_CLUSTER).build())
        .build()
}
