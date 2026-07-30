package com.sqmusicplus.v3.lyric.vo;

import lombok.Data;

@Data
public class LyricRepairRequest {

    /** Whether an existing non-empty sidecar lyric file may be replaced. */
    private Boolean overwriteExisting = false;
}
