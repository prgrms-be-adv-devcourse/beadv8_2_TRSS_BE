package com.backend.standard.modelType;

/**
 * 모델 종류를 문자열 코드로 구분할 수 있는 타입.
 * 원장·이력에 "어떤 대상(Order, Settlement 등)의 몇 번"인지 남길 때 사용한다.
 */
public interface HasModelTypeCode {
    String getModelTypeCode();
}
