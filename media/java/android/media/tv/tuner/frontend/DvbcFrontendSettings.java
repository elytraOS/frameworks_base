/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.tv.tuner.V1_0.Constants;
import android.media.tv.tuner.TunerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Frontend settings for DVBC.
 *
 * @hide
 */
@SystemApi
public class DvbcFrontendSettings extends FrontendSettings {

    /** @hide */
    @IntDef(flag = true,
            prefix = "MODULATION_",
            value = {MODULATION_UNDEFINED, MODULATION_AUTO, MODULATION_MOD_16QAM,
                    MODULATION_MOD_32QAM, MODULATION_MOD_64QAM, MODULATION_MOD_128QAM,
                    MODULATION_MOD_256QAM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Modulation {}

    /**
     * Modulation undefined.
     */
    public static final int MODULATION_UNDEFINED = Constants.FrontendDvbcModulation.UNDEFINED;
    /**
     * Hardware is able to detect and set modulation automatically
     */
    public static final int MODULATION_AUTO = Constants.FrontendDvbcModulation.AUTO;
    /**
     * 16QAM Modulation.
     */
    public static final int MODULATION_MOD_16QAM = Constants.FrontendDvbcModulation.MOD_16QAM;
    /**
     * 32QAM Modulation.
     */
    public static final int MODULATION_MOD_32QAM = Constants.FrontendDvbcModulation.MOD_32QAM;
    /**
     * 64QAM Modulation.
     */
    public static final int MODULATION_MOD_64QAM = Constants.FrontendDvbcModulation.MOD_64QAM;
    /**
     * 128QAM Modulation.
     */
    public static final int MODULATION_MOD_128QAM = Constants.FrontendDvbcModulation.MOD_128QAM;
    /**
     * 256QAM Modulation.
     */
    public static final int MODULATION_MOD_256QAM = Constants.FrontendDvbcModulation.MOD_256QAM;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "OUTER_FEC_",
            value = {OUTER_FEC_UNDEFINED, OUTER_FEC_OUTER_FEC_NONE, OUTER_FEC_OUTER_FEC_RS})
    public @interface OuterFec {}

    /**
     * Outer Forward Error Correction (FEC) Type undefined.
     */
    public static final int OUTER_FEC_UNDEFINED = Constants.FrontendDvbcOuterFec.UNDEFINED;
    /**
     * None Outer Forward Error Correction (FEC) Type.
     */
    public static final int OUTER_FEC_OUTER_FEC_NONE =
            Constants.FrontendDvbcOuterFec.OUTER_FEC_NONE;
    /**
     * RS Outer Forward Error Correction (FEC) Type.
     */
    public static final int OUTER_FEC_OUTER_FEC_RS = Constants.FrontendDvbcOuterFec.OUTER_FEC_RS;


    /** @hide */
    @IntDef(flag = true,
            prefix = "ANNEX_",
            value = {ANNEX_UNDEFINED, ANNEX_A, ANNEX_B, ANNEX_C})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Annex {}

    /**
     * Annex Type undefined.
     */
    public static final int ANNEX_UNDEFINED = Constants.FrontendDvbcAnnex.UNDEFINED;
    /**
     * Annex Type A.
     */
    public static final int ANNEX_A = Constants.FrontendDvbcAnnex.A;
    /**
     * Annex Type B.
     */
    public static final int ANNEX_B = Constants.FrontendDvbcAnnex.B;
    /**
     * Annex Type C.
     */
    public static final int ANNEX_C = Constants.FrontendDvbcAnnex.C;


    /** @hide */
    @IntDef(prefix = "SPECTRAL_INVERSION_",
            value = {SPECTRAL_INVERSION_UNDEFINED, SPECTRAL_INVERSION_NORMAL,
                    SPECTRAL_INVERSION_INVERTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SpectralInversion {}

    /**
     * Spectral Inversion Type undefined.
     */
    public static final int SPECTRAL_INVERSION_UNDEFINED =
            Constants.FrontendDvbcSpectralInversion.UNDEFINED;
    /**
     * Normal Spectral Inversion.
     */
    public static final int SPECTRAL_INVERSION_NORMAL =
            Constants.FrontendDvbcSpectralInversion.NORMAL;
    /**
     * Inverted Spectral Inversion.
     */
    public static final int SPECTRAL_INVERSION_INVERTED =
            Constants.FrontendDvbcSpectralInversion.INVERTED;


    private final int mModulation;
    private final long mFec;
    private final int mSymbolRate;
    private final int mOuterFec;
    private final int mAnnex;
    private final int mSpectralInversion;

    private DvbcFrontendSettings(int frequency, int modulation, long fec, int symbolRate,
            int outerFec, int annex, int spectralInversion) {
        super(frequency);
        mModulation = modulation;
        mFec = fec;
        mSymbolRate = symbolRate;
        mOuterFec = outerFec;
        mAnnex = annex;
        mSpectralInversion = spectralInversion;
    }

    /**
     * Gets Modulation.
     */
    @Modulation
    public int getModulation() {
        return mModulation;
    }
    /**
     * Gets Inner Forward Error Correction.
     */
    @InnerFec
    public long getFec() {
        return mFec;
    }
    /**
     * Gets Symbol Rate in symbols per second.
     */
    public int getSymbolRate() {
        return mSymbolRate;
    }
    /**
     * Gets Outer Forward Error Correction.
     */
    @OuterFec
    public int getOuterFec() {
        return mOuterFec;
    }
    /**
     * Gets Annex.
     */
    @Annex
    public int getAnnex() {
        return mAnnex;
    }
    /**
     * Gets Spectral Inversion.
     */
    @SpectralInversion
    public int getSpectralInversion() {
        return mSpectralInversion;
    }

    /**
     * Creates a builder for {@link DvbcFrontendSettings}.
     *
     * @param context the context of the caller.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_TV_TUNER)
    @NonNull
    public static Builder builder(@NonNull Context context) {
        TunerUtils.checkTunerPermission(context);
        return new Builder();
    }

    /**
     * Builder for {@link DvbcFrontendSettings}.
     */
    public static class Builder extends FrontendSettings.Builder<Builder> {
        private int mModulation;
        private long mFec;
        private int mSymbolRate;
        private int mOuterFec;
        private int mAnnex;
        private int mSpectralInversion;

        private Builder() {
        }

        /**
         * Sets Modulation.
         */
        @NonNull
        public Builder setModulation(@Modulation int modulation) {
            mModulation = modulation;
            return this;
        }
        /**
         * Sets Inner Forward Error Correction.
         */
        @NonNull
        public Builder setFec(@InnerFec long fec) {
            mFec = fec;
            return this;
        }
        /**
         * Sets Symbol Rate in symbols per second.
         */
        @NonNull
        public Builder setSymbolRate(int symbolRate) {
            mSymbolRate = symbolRate;
            return this;
        }
        /**
         * Sets Outer Forward Error Correction.
         */
        @NonNull
        public Builder setOuterFec(@OuterFec int outerFec) {
            mOuterFec = outerFec;
            return this;
        }
        /**
         * Sets Annex.
         */
        @NonNull
        public Builder setAnnex(@Annex int annex) {
            mAnnex = annex;
            return this;
        }
        /**
         * Sets Spectral Inversion.
         */
        @NonNull
        public Builder setSpectralInversion(@SpectralInversion int spectralInversion) {
            mSpectralInversion = spectralInversion;
            return this;
        }

        /**
         * Builds a {@link DvbcFrontendSettings} object.
         */
        @NonNull
        public DvbcFrontendSettings build() {
            return new DvbcFrontendSettings(mFrequency, mModulation, mFec, mSymbolRate, mOuterFec,
                    mAnnex, mSpectralInversion);
        }

        @Override
        Builder self() {
            return this;
        }
    }

    @Override
    public int getType() {
        return FrontendSettings.TYPE_DVBC;
    }
}
