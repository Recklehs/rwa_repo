# AKS Deployment (Legacy Record, Disabled)

이 디렉터리는 발표 기간에 사용했던 Azure AKS 배포 기록 보존용입니다.

- `create-env-secret.sh`, `deploy.sh` 는 의도적으로 실행 차단되어 있습니다.
- 파일은 추적/회고 목적 때문에 유지합니다.
- 실제 실행은 루트 `README.md`의 로컬 실행 절차를 사용하세요.

## Historical Notes

아래는 당시 운영에 사용했던 변수/흐름 기록입니다(현재 실행 불가).

- Namespace/App: `cryptoorder`, `cryptoorder-server`
- 이미지 예시: `2dtteam4temp.azurecr.io/cryptoorder-server:<tag>`
- 주요 리소스: ConfigMap/Secret/JWT Secret/Deployment/Service/HPA
