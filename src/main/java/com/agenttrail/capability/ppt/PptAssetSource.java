package com.agenttrail.capability.ppt;

/** 素材的可审计来源，不把短时 URL 当成来源标识。 */
public enum PptAssetSource {
    TEXT_TO_IMAGE,
    IMAGE_SEARCH,
    CHART_GENERATED,
    PROGRAMMATIC,
    TEMPLATE_ORIGINAL
}
