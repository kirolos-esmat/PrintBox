package com.usbprint.server

object ZjsNative {
    init {
        try {
            System.loadLibrary("zjsencoder")
            AppState.log("Native ZJS encoder loaded successfully.")
        } catch (e: Throwable) {
            AppState.log("Failed to load native ZJS encoder: ${e.message}", isError = true)
        }
    }

    /**
     * Converts a single or multi-page PBM file (P4 raw binary format) into
     * an HP LaserJet Pro P1102 ZjStream (ZJS) file.
     *
     * @param pbmPath absolute path to the input PBM file
     * @param zjsPath absolute path to the output ZJS file
     * @param paperCode 9 = A4, 1 = Letter
     * @param copies number of copies
     * @return true if successful
     */
    external fun convertPbmToZjs(
        pbmPath: String,
        zjsPath: String,
        paperCode: Int = 9,
        copies: Int = 1
    ): Boolean
}
