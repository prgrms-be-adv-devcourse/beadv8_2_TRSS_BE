package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductImage;
import com.backend.boundedContext.product.domain.ProductImageType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.LocalProductImageStorage;
import com.backend.boundedContext.product.out.ProductImageRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static com.backend.boundedContext.product.ProductImageFixtures.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * DB 트랜잭션 결과에 따라 이미지 파일이 정리되는지 확인한다. 커밋·롤백이 실제로 일어나야 하므로 @Transactional을 붙이지 않고,
 * 커밋된 데이터와 파일은 @AfterEach에서 직접 지운다.
 */
@SpringBootTest
class ProductImageFileCleanupTest {

    private static final long MEMBER_ID = 700L;
    private static final long SELLER_ID = 9_700L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private LocalProductImageStorage localProductImageStorage;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private MemberApi memberApi;

    private final List<Long> committedProductIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID))
                .willReturn(new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED));
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> committedProductIds.forEach(productId -> {
            productImageRepository.findAll().stream()
                    .filter(image -> image.getProduct().getId().equals(productId))
                    .forEach(image -> {
                        localProductImageStorage.delete(image.getImageKey());
                        productImageRepository.delete(image);
                    });
            productOptionRepository.findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(List.of(productId)).forEach(option -> {
                stockHistoryRepository.deleteAll(stockHistoryRepository.findAll().stream()
                        .filter(history -> history.getOption().getId().equals(option.getId()))
                        .toList());
                productOptionRepository.delete(option);
            });
            productRepository.deleteById(productId);
        }));
    }

    @Test
    @DisplayName("등록 트랜잭션이 롤백되면 저장했던 이미지 파일도 지워진다")
    void rollbackDeletesStoredFiles() {
        List<Path> stored = new ArrayList<>();

        transactionTemplate.executeWithoutResult(status -> {
            Long productId = create();
            images(productId).forEach(image -> stored.add(fileOf(image)));
            assertThat(stored).isNotEmpty().allSatisfy(path -> assertThat(path).exists());
            status.setRollbackOnly();
        });

        assertThat(stored).allSatisfy(path -> assertThat(path).doesNotExist());
    }

    @Test
    @DisplayName("대표 이미지를 교체하면, 커밋된 뒤에 옛 파일이 지워지고 새 파일은 남는다")
    void replacedFileIsDeletedAfterCommit() {
        Long productId = create();
        committedProductIds.add(productId);
        Path oldFile = fileOf(images(productId).getFirst());

        productFacade.modifyProduct(MEMBER_ID, productId, null, null, null, null, null, jpeg(), null);

        assertThat(oldFile).doesNotExist();
        assertThat(images(productId)).singleElement().satisfies(image -> assertThat(fileOf(image)).exists());
    }

    private Long create() {
        Long skincareId = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow().getId();
        return productFacade.createProduct(MEMBER_ID, skincareId, "파일 정리 테스트", "설명", 25_000, 10, THUMBNAIL, List.of());
    }

    private List<ProductImage> images(Long productId) {
        return productImageRepository.findAllByProductIdAndImageTypeOrderBySortOrderAscIdAsc(productId, ProductImageType.THUMBNAIL);
    }

    private Path fileOf(ProductImage image) {
        return localProductImageStorage.getRootDir().resolve(image.getImageKey());
    }
}
