# 상품 검색용 Elasticsearch 이미지. 로컬(compose.yaml)과 데이터 서버 EC2-B(compose.data.yaml)가 같이 쓴다.
# 버전은 Spring Boot 4.1.1이 쓰는 ES 클라이언트(elasticsearch-client.version)와 맞춘다. Boot를 올리면 이 값도 같이 올린다.
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.5

# 한글 형태소 분석기(nori). 플러그인 버전은 ES 버전과 같아야 해서 이미지를 만들 때 한 번만 넣는다(재시작마다 받지 않는다)
RUN bin/elasticsearch-plugin install --batch analysis-nori
