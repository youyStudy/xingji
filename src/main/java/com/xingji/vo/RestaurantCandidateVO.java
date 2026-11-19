package com.xingji.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 餐饮候选(来自高德的真实餐厅),喂给模型的"可选餐厅池"。
 *
 * 只含模型挑选和渲染提示词需要的最小字段:
 * 名字用来让模型认得出、人均用来让模型估预算、area 用来让模型知道这家在哪一带。
 */
@Data
public class RestaurantCandidateVO {

    /** 高德 POI ID,模型必须用它来指定选中的餐厅 */
    private String amapPoiId;

    /** 餐厅名 */
    private String name;

    /** 所属区域/景点名(如"武侯祠"),帮模型判断地理位置是否顺路 */
    private String area;

    /** 人均消费(元),高德 biz_ext 提供,可能为空 */
    private String cost;

    /** 菜系/类型描述,如"川菜" "火锅" */
    private String category;

    /** GCJ-02 经度(落库时用) */
    private BigDecimal lng;

    /** GCJ-02 纬度(落库时用) */
    private BigDecimal lat;
}
