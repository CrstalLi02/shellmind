# Plain image build; follows host arch (amd/arm)
#docker build -t fuzhengwei/shellmind-server-app:1.0 -f ./Dockerfile .

# Multi-arch build for amd64 and arm64
docker build --platform linux/amd64,linux/arm64 --load -t fuzhengwei/shellmind-server-app:1.1 -f ./Dockerfile .