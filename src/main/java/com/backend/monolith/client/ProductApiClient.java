package com.backend.monolith.client;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;

/**
 * 상품이 market-service로 옮겨 간 뒤 루트 모놀리스에 남은 임시 구현.
 * 회원(판매자 철회·제재)이 쓰는 stopAllBySeller·resumeAllBySeller는 상품 상태를 바꾸는 호출이라
 * 아무것도 안 한 채 성공하면 안 되므로 예외를 던진다.
 * TODO 서비스 간 연결 작업에서 market-service 내부 API를 호출하도록 교체 (회원 서비스 이동 때 그쪽으로 옮김)
 */
@Component
public class ProductApiClient implements ProductApi {

    @Override
    public List<OptionSnapshotDto> getOptions(Collection<Long> optionIds) {
        throw notConnected();
    }

    @Override
    public void deductStock(List<StockChangeDto> items) {
        throw notConnected();
    }

    @Override
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        throw notConnected();
    }

    @Override
    public void lockProductsForOrderChange(Collection<Long> productIds) {
        throw notConnected();
    }

    @Override
    public void stopAllBySeller(Long sellerId, String reason) {
        throw notConnected();
    }

    @Override
    public void resumeAllBySeller(Long sellerId) {
        throw notConnected();
    }

    private UnsupportedOperationException notConnected() {
        return new UnsupportedOperationException("market-service 연결 전이라 상품 API를 호출할 수 없습니다");
    }
}
