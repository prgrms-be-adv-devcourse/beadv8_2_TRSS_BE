rootProject.name = "demo"

// 루트 src는 기존 모놀리스. 서비스 이동 PR마다 각 모듈로 옮겨지며 비워진다.
include(
	"common",
	"gateway",
	"member-service",
	"market-service",
	"payment-service",
	"settlement-service",
	"ai-service",
)
