package com.agenttrail.sys.datascope;

/** 数据范围从宽到窄的固定顺序，禁止按字符串排序推导权限宽度。 */
public enum DataScope {
    ALL(4), DEPT_AND_SUB(3), DEPT(2), SELF(1);

    private final int width;
    DataScope(int width) { this.width = width; }

    public int compareByWidth(DataScope other) { return Integer.compare(width, other.width); }
    public boolean widerThan(DataScope other) { return compareByWidth(other) > 0; }
}
