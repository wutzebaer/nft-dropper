call mvn package -DskipTests=true
docker build -t wutzebaer/nft-dropper:latest . 
docker push wutzebaer/nft-dropper:latest
pause