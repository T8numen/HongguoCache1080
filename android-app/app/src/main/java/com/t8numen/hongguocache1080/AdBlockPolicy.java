package com.t8numen.hongguocache1080;

/** Experimental categories are independent; unknown never overrides a disabled known category. */
final class AdBlockPolicy {
    static final int PAUSE = 1;
    static final int GAME = 2;
    static final int LIVE = 4;
    static final int UNKNOWN = 8;
    static final int EARLY_CONFIG = 16;
    static final int EARLY_REQUEST = 32;
    static final int NO_AD_ENTITLEMENT = 64;

    private AdBlockPolicy() { }

    static boolean blocks(int category, int enabled) {
        return category != 0 && (enabled & category) != 0;
    }

    static boolean blockPosition(String position, int enabled) {
        return blocks(EARLY_REQUEST, enabled)
                && ("short_series_ad".equals(position)
                || "landscape_short_series_ad".equals(position));
    }

    static boolean supportsEarlyRules(String packageName, String version, long code) {
        return ("com.phoenix.read".equals(packageName) && "7.3.9.32".equals(version) && code == 73932L)
                || ("com.kylin.read".equals(packageName) && "7.4.1.32".equals(version) && code == 74132L);
    }

    static boolean playableLanding(String activity, String pageType) {
        return "playable".equals(pageType)
                && ("com.dragon.read.ad.dark.ui.AdLandingActivity".equals(activity)
                || "com.dragon.read.ad.dark.ui.NewAdLandingActivity".equals(activity)
                || "com.dragon.read.ad.dark.ui.AdSubmitDialogActivity".equals(activity)
                || "com.dragon.read.ad.dark.ui.NewAdSubmitDialogActivity".equals(activity));
    }

    static int pageCategory(String className) {
        return pageCategory(className, false);
    }

    static int pageCategory(String className, boolean normalVideoWrapper) {
        if (className == null) {
            return 0;
        }
        if ("com.dragon.read.feed.bookmall.subtab.model.VideoTabSingleLiveModel".equals(className)) {
            return LIVE;
        }
        if ("com.dragon.read.feed.bookmall.subtab.model.VideoTabSingleMiniGameModel".equals(className)
                || "com.dragon.read.component.shortvideo.api.directgame.singlecolumn.DirectGameSingleColumnModel".equals(className)) {
            return GAME;
        }
        if (normalVideoWrapper
                || "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoData".equals(className)
                || "com.dragon.read.component.shortvideo.data.saas.video.SaasVideoDataWrapper".equals(className)
                || "com.dragon.read.component.shortvideo.data.ugc.SaaSUgcPostDataWrapper".equals(className)
                || "com.dragon.read.component.shortvideo.data.ugc.SaaSSeriesUgcPostDataWrapper".equals(className)
                || "com.dragon.read.pages.bookmall.model.VideoTabModel".equals(className)) {
            return 0;
        }
        return UNKNOWN;
    }
}
