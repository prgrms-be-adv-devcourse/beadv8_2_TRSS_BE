package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductImageDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductImage;
import com.backend.boundedContext.product.domain.ProductImageType;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.LocalProductImageStorage;
import com.backend.boundedContext.product.out.ProductImageRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.dto.SellerStatus;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.out.OrderApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static com.backend.boundedContext.product.ProductImageFixtures.PNG_BYTES;
import static com.backend.boundedContext.product.ProductImageFixtures.THUMBNAIL;
import static com.backend.boundedContext.product.ProductImageFixtures.gif;
import static com.backend.boundedContext.product.ProductImageFixtures.jpeg;
import static com.backend.boundedContext.product.ProductImageFixtures.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;

/**
 * 상품 이미지 저장·교체와 조회 응답의 URL을 Facade로 검증한다. 파일은 로컬 저장소(테스트는 build/test-uploads)에 저장된다.
 * 테스트마다 롤백되고, 롤백되면 새로 저장한 파일도 지워진다.
 */
@SpringBootTest
@Transactional
class ProductFacadeImageTest {

    private static final long MEMBER_ID = 600L;
    private static final long SELLER_ID = 9_600L;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private LocalProductImageStorage localProductImageStorage;

    @Value("${custom.product.image.baseUrl}")
    private String baseUrl;

    @MockitoBean
    private MemberApi memberApi;

    /** 주문 쪽 구현과 분리하고, 다른 상품 통합 테스트와 mock 조합을 맞춰 테스트 컨텍스트(DB 연결 풀)를 같이 쓴다 */
    @MockitoBean
    private OrderApi orderApi;

    private Category skincare;

    @BeforeEach
    void setUp() {
        SellerDto seller = new SellerDto(SELLER_ID, MEMBER_ID, "팔레트 상점", SellerStatus.APPROVED);
        given(memberApi.getApprovedSellerByMemberId(MEMBER_ID)).willReturn(seller);
        given(memberApi.getSellers(anyCollection())).willReturn(List.of(seller));
        skincare = categoryRepository.findByParentNameAndName("뷰티", "스킨케어").orElseThrow();
    }

    @Test
    @DisplayName("등록: 대표 이미지와 상세 이미지를 순서대로 저장하고, 파일은 products/{상품ID}/ 아래에 형식에 맞는 확장자로 저장된다")
    void createWithImages() {
        Long productId = create(THUMBNAIL, List.of(jpeg(), png()));

        assertThat(images(productId, ProductImageType.THUMBNAIL)).singleElement().satisfies(image -> {
            assertThat(image.getImageKey()).startsWith("products/" + productId + "/").endsWith(".png");
            assertThat(fileOf(image)).exists().hasBinaryContent(PNG_BYTES);
        });
        assertThat(images(productId, ProductImageType.DETAIL))
                .extracting(image -> image.getImageKey().substring(image.getImageKey().lastIndexOf('.')), ProductImage::getSortOrder)
                .containsExactly(
                        tuple(".jpg", 0),
                        tuple(".png", 1));
    }

    @Test
    @DisplayName("등록: 대표 이미지가 없으면 THUMBNAIL_REQUIRED, jpg·png·webp가 아니면 IMAGE_TYPE_NOT_ALLOWED")
    void createWithInvalidImages() {
        assertThatThrownBy(() -> create(null, List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-THUMBNAIL_REQUIRED");
        assertThatThrownBy(() -> create(gif(), List.of()))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-IMAGE_TYPE_NOT_ALLOWED");
        assertThatThrownBy(() -> create(THUMBNAIL, List.of(png(), gif())))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-IMAGE_TYPE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("등록: 상세 이미지가 10개를 넘으면 DETAIL_IMAGE_LIMIT_EXCEEDED, 10개까지는 된다")
    void createWithTooManyDetails() {
        assertThatThrownBy(() -> create(THUMBNAIL, Collections.nCopies(11, png())))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DETAIL_IMAGE_LIMIT_EXCEEDED");

        Long productId = create(THUMBNAIL, Collections.nCopies(10, png()));
        assertThat(images(productId, ProductImageType.DETAIL)).hasSize(10);
    }

    @Test
    @DisplayName("수정: 대표 이미지를 보내면 교체하고, 보내지 않으면 그대로다")
    void modifyThumbnail() {
        Long productId = create(THUMBNAIL, List.of());
        ProductImage before = images(productId, ProductImageType.THUMBNAIL).getFirst();

        modify(productId, null, null, null);
        assertThat(images(productId, ProductImageType.THUMBNAIL)).containsExactly(before);

        modify(productId, jpeg(), null, null);
        assertThat(images(productId, ProductImageType.THUMBNAIL)).singleElement().satisfies(image -> {
            assertThat(image.getId()).isNotEqualTo(before.getId());
            assertThat(image.getImageKey()).endsWith(".jpg");
        });
    }

    @Test
    @DisplayName("수정: detailImageIds의 순서대로 남기고 빠진 이미지는 지우며, 새 상세 이미지는 뒤에 붙인다")
    void modifyDetailImages() {
        Long productId = create(THUMBNAIL, List.of(png(), png(), png()));
        List<Long> ids = detailIds(productId);

        modify(productId, null, List.of(ids.get(2), ids.get(0)), List.of(jpeg()));

        List<ProductImage> details = images(productId, ProductImageType.DETAIL);
        assertThat(details).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2);
        assertThat(details.subList(0, 2)).extracting(ProductImage::getId).containsExactly(ids.get(2), ids.get(0));
        assertThat(details.get(2).getImageKey()).endsWith(".jpg");
    }

    @Test
    @DisplayName("수정: detailImageIds를 보내지 않으면 기존 상세 이미지를 그대로 두고, 빈 목록이면 모두 지운다")
    void modifyDetailImagesNullOrEmpty() {
        Long productId = create(THUMBNAIL, List.of(png(), png()));
        List<Long> ids = detailIds(productId);

        modify(productId, null, null, List.of(jpeg()));
        assertThat(detailIds(productId)).hasSize(3).startsWith(ids.get(0), ids.get(1));

        modify(productId, null, List.of(), null);
        assertThat(detailIds(productId)).isEmpty();
    }

    @Test
    @DisplayName("수정: 다른 상품의 이미지 ID나 같은 ID를 두 번 보내면 DETAIL_IMAGE_NOT_FOUND, 합쳐서 10개를 넘으면 DETAIL_IMAGE_LIMIT_EXCEEDED")
    void modifyDetailImagesInvalid() {
        Long productId = create(THUMBNAIL, List.of(png()));
        Long otherProductId = create(THUMBNAIL, List.of(png()));
        Long mine = detailIds(productId).getFirst();
        Long others = detailIds(otherProductId).getFirst();

        assertThatThrownBy(() -> modify(productId, null, List.of(others), null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DETAIL_IMAGE_NOT_FOUND");
        assertThatThrownBy(() -> modify(productId, null, List.of(mine, mine), null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DETAIL_IMAGE_NOT_FOUND");
        assertThatThrownBy(() -> modify(productId, null, List.of(mine), Collections.nCopies(10, png())))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-DETAIL_IMAGE_LIMIT_EXCEEDED");
        assertThat(detailIds(productId)).containsExactly(mine);
    }

    @Test
    @DisplayName("수정: 판매 중지된 상품은 이미지만 바꿔도 PRODUCT_NOT_EDITABLE")
    void modifyImagesOfStoppedProduct() {
        Long productId = create(THUMBNAIL, List.of());
        ReflectionTestUtils.setField(productRepository.findById(productId).orElseThrow(), "status", ProductSaleStatus.STOPPED);

        assertThatThrownBy(() -> modify(productId, jpeg(), null, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("조회: 검색 목록·상세·판매자 목록·수정 폼에 이미지 URL(baseUrl + 키)이 나온다")
    void imageUrlsInResponses() {
        Long productId = create(THUMBNAIL, List.of(jpeg(), png()));
        String name = productRepository.findById(productId).orElseThrow().getName();
        String thumbnailUrl = url(images(productId, ProductImageType.THUMBNAIL).getFirst());
        List<String> detailUrls = images(productId, ProductImageType.DETAIL).stream().map(this::url).toList();

        assertThat(productFacade.searchProducts(name, null, ProductSort.LATEST, true, PageRequest.of(0, 20)).items())
                .singleElement().satisfies(item -> assertThat(item.thumbnailUrl()).isEqualTo(thumbnailUrl));
        assertThat(productFacade.getProductDetail(productId)).satisfies(detail -> {
            assertThat(detail.thumbnailUrl()).isEqualTo(thumbnailUrl);
            assertThat(detail.imageUrls()).containsExactlyElementsOf(detailUrls);
        });
        assertThat(productFacade.getSellerProducts(MEMBER_ID, null, name, PageRequest.of(0, 20)).items())
                .singleElement().satisfies(item -> assertThat(item.thumbnailUrl()).isEqualTo(thumbnailUrl));

        SellerProductFormDto form = productFacade.getSellerProductForm(MEMBER_ID, productId);
        assertThat(form.thumbnail().url()).isEqualTo(thumbnailUrl);
        assertThat(form.detailImages()).extracting(ProductImageDto::imageId).containsExactlyElementsOf(detailIds(productId));
        assertThat(form.detailImages()).extracting(ProductImageDto::url).containsExactlyElementsOf(detailUrls);
    }

    /** 상품명이 테스트마다 달라야 검색 결과가 섞이지 않는다 */
    private Long create(ProductImageFile thumbnail, List<ProductImageFile> details) {
        return productFacade.createProduct(MEMBER_ID, skincare.getId(), "이미지 테스트 " + System.nanoTime(), "설명",
                25_000, 10, thumbnail, details);
    }

    private void modify(Long productId, ProductImageFile thumbnail, List<Long> keepIds, List<ProductImageFile> newDetails) {
        productFacade.modifyProduct(MEMBER_ID, productId, null, null, null, null, keepIds, thumbnail, newDetails);
    }

    private List<ProductImage> images(Long productId, ProductImageType type) {
        return productImageRepository.findAllByProductIdAndImageTypeOrderBySortOrderAscIdAsc(productId, type);
    }

    private List<Long> detailIds(Long productId) {
        return images(productId, ProductImageType.DETAIL).stream().map(ProductImage::getId).toList();
    }

    private Path fileOf(ProductImage image) {
        return localProductImageStorage.getRootDir().resolve(image.getImageKey());
    }

    private String url(ProductImage image) {
        return baseUrl + "/" + image.getImageKey();
    }
}
