package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductImageDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductImage;
import com.backend.boundedContext.product.domain.ProductImageFormat;
import com.backend.boundedContext.product.domain.ProductImageType;
import com.backend.boundedContext.product.domain.ProductPolicy;
import com.backend.boundedContext.product.out.ProductImageRepository;
import com.backend.boundedContext.product.out.ProductImageStorage;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 상품 이미지 저장·교체와 조회. 파일은 저장소(ProductImageStorage)에, 키는 product_image에 둔다.
 *
 * <p>DB와 파일이 어긋나지 않도록, 새로 저장한 파일은 트랜잭션이 롤백되면 지우고,
 * 교체·삭제된 옛 파일은 커밋이 끝난 뒤에 지운다 (커밋 전에 지웠다가 롤백되면 DB가 없는 파일을 가리킨다).
 * 형식·개수 검사는 파일을 하나라도 저장하기 전에 모두 끝낸다.
 */
@Service
@RequiredArgsConstructor
public class ProductImageSupport {
    private final ProductImageRepository productImageRepository;
    private final ProductImageStorage productImageStorage;
    private final ProductPolicy productPolicy;

    /** 등록: 대표 이미지(필수)와 상세 이미지(선택, 순서대로)를 저장한다. 상품은 저장되어 ID가 있어야 한다. */
    public void addImages(Product product, ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        if (thumbnail == null) {
            throw new DomainException(ProductErrorCode.THUMBNAIL_REQUIRED);
        }
        List<ProductImageFile> details = nullToEmpty(detailImages);
        checkDetailCount(details.size());
        ProductImageFormat thumbnailFormat = ProductImageFormat.detect(thumbnail.content());
        List<ProductImageFormat> detailFormats = detectAll(details);

        productImageRepository.save(ProductImage.thumbnail(product, store(product, thumbnail, thumbnailFormat)));
        for (int i = 0; i < details.size(); i++) {
            productImageRepository.save(ProductImage.detail(product, store(product, details.get(i), detailFormats.get(i)), i));
        }
    }

    /**
     * 수정: 보낸 것만 바꾼다.
     * <ul>
     *   <li>thumbnail이 있으면 대표 이미지를 교체한다.</li>
     *   <li>keepDetailImageIds가 있으면 그 상세 이미지만 그 순서대로 남기고 나머지는 지운다. null이면 기존 상세 이미지를 그대로 둔다.</li>
     *   <li>newDetailImages는 남긴 상세 이미지 뒤에 붙인다. 합쳐서 최대 개수를 넘으면 400.</li>
     * </ul>
     */
    public void modifyImages(Product product, ProductImageFile thumbnail, List<Long> keepDetailImageIds,
                             List<ProductImageFile> newDetailImages) {
        List<ProductImageFile> newDetails = nullToEmpty(newDetailImages);
        List<ProductImage> currentDetails = findImages(product.getId(), ProductImageType.DETAIL);
        List<ProductImage> keptDetails = keepDetailImageIds == null
                ? currentDetails
                : pickDetails(currentDetails, keepDetailImageIds);
        checkDetailCount(keptDetails.size() + newDetails.size());
        ProductImageFormat thumbnailFormat = thumbnail == null ? null : ProductImageFormat.detect(thumbnail.content());
        List<ProductImageFormat> newDetailFormats = detectAll(newDetails);

        if (thumbnail != null) {
            findImages(product.getId(), ProductImageType.THUMBNAIL).forEach(this::remove);
            productImageRepository.save(ProductImage.thumbnail(product, store(product, thumbnail, thumbnailFormat)));
        }

        currentDetails.stream().filter(image -> !keptDetails.contains(image)).forEach(this::remove);
        for (int i = 0; i < keptDetails.size(); i++) {
            keptDetails.get(i).changeSortOrder(i);
        }
        for (int i = 0; i < newDetails.size(); i++) {
            String key = store(product, newDetails.get(i), newDetailFormats.get(i));
            productImageRepository.save(ProductImage.detail(product, key, keptDetails.size() + i));
        }
    }

    /** 상품 ID → 대표 이미지 URL. 대표 이미지가 없는 상품은 키가 없다. */
    public Map<Long, String> getThumbnailUrls(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();

        return productImageRepository.findAllByProductIdInAndImageType(productIds, ProductImageType.THUMBNAIL).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(),
                        image -> productImageStorage.getUrl(image.getImageKey()), (first, second) -> first));
    }

    /** 대표 이미지. 없으면 null */
    public ProductImageDto getThumbnail(Long productId) {
        return findImages(productId, ProductImageType.THUMBNAIL).stream().findFirst().map(this::toDto).orElse(null);
    }

    /** 상세 이미지 (보여 줄 순서대로) */
    public List<ProductImageDto> getDetailImages(Long productId) {
        return findImages(productId, ProductImageType.DETAIL).stream().map(this::toDto).toList();
    }

    private List<ProductImage> findImages(Long productId, ProductImageType imageType) {
        return productImageRepository.findAllByProductIdAndImageTypeOrderBySortOrderAscIdAsc(productId, imageType);
    }

    /** 남길 상세 이미지를 요청 순서대로 고른다. 이 상품의 상세 이미지가 아니거나 같은 ID가 두 번 오면 400. */
    private List<ProductImage> pickDetails(List<ProductImage> currentDetails, List<Long> keepIds) {
        if (keepIds.stream().anyMatch(Objects::isNull) || new HashSet<>(keepIds).size() != keepIds.size()) {
            throw new DomainException(ProductErrorCode.DETAIL_IMAGE_NOT_FOUND);
        }
        Map<Long, ProductImage> byId = currentDetails.stream()
                .collect(Collectors.toMap(ProductImage::getId, Function.identity()));
        return keepIds.stream()
                .map(id -> {
                    ProductImage image = byId.get(id);
                    if (image == null) throw new DomainException(ProductErrorCode.DETAIL_IMAGE_NOT_FOUND);
                    return image;
                })
                .toList();
    }

    private void checkDetailCount(int count) {
        if (count > productPolicy.getMaxDetailImages()) {
            throw new DomainException(ProductErrorCode.DETAIL_IMAGE_LIMIT_EXCEEDED);
        }
    }

    private static List<ProductImageFormat> detectAll(List<ProductImageFile> files) {
        return files.stream().map(file -> ProductImageFormat.detect(file.content())).toList();
    }

    /** 파일을 새 키로 저장하고, 트랜잭션이 롤백되면 지우도록 걸어 둔다. */
    private String store(Product product, ProductImageFile file, ProductImageFormat format) {
        String key = "products/" + product.getId() + "/" + UUID.randomUUID() + "." + format.getExtension();
        productImageStorage.store(key, file.content(), format.getContentType());
        afterTransaction(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) productImageStorage.delete(key);
            }
        });
        return key;
    }

    /** 이미지 행을 지우고, 파일은 커밋이 끝난 뒤에 지운다. */
    private void remove(ProductImage image) {
        productImageRepository.delete(image);
        String key = image.getImageKey();
        afterTransaction(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                productImageStorage.delete(key);
            }
        });
    }

    private static void afterTransaction(TransactionSynchronization synchronization) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("상품 이미지는 트랜잭션 안에서만 저장·삭제한다 (ProductFacade)");
        }
        TransactionSynchronizationManager.registerSynchronization(synchronization);
    }

    private ProductImageDto toDto(ProductImage image) {
        return new ProductImageDto(image.getId(), productImageStorage.getUrl(image.getImageKey()));
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
