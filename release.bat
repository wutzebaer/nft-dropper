call mvn spring-boot:build-image -Dspring-boot.build-image.imageName=wutzebaer/nft-dropper:latest -Dmaven.test.skip
docker push wutzebaer/nft-dropper:latest
pause