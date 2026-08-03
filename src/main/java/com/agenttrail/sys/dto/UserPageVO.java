package com.agenttrail.sys.dto;

import java.util.List;

public record UserPageVO(List<UserVO> items, long total, int page, int size) {
}
