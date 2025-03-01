package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * @author 30241
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
