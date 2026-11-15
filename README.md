[README.md](https://github.com/user-attachments/files/34879847/README.md)
# 카푸리 (carpuri)

차량의 승하차 정보를 기록하고, 함께 탄 구간에 맞춰 카풀 비용을 계산하는 Pleos Connect 앱입니다.

## 주요 기능

* 조수석·뒷좌석 승하차 감지
* 탑승자 등록 및 사람·짐·오류 분류 수정
* 다른 앱을 사용하는 동안에도 승하차 기록
* 탑승 구간별 비용 계산 및 정산 내역 저장

## 실행 방법

1. 압축을 풀고 Android Studio에서 `carpuri` 폴더를 엽니다.
2. Android SDK 35를 설치하고 Gradle Sync를 완료합니다.
3. Pleos Connect 에뮬레이터를 선택한 뒤 `app`을 실행합니다.
4. 차량 속도·알림 권한을 허용하고 앱에서 `실제 차량 입력`을 선택합니다.

앱을 직접 열면 기록을 시작합니다. 탑승자 정보는 P단 또는 정차 상태에서 입력할 수 있으며, 차량의 입력 제한이 우선 적용됩니다.

## 개발 및 테스트 환경

|항목|내용|
|-|-|
|개발 도구|Android Studio, Kotlin, Jetpack Compose|
|Android SDK|35|
|Pleos Vehicle SDK|2.3.2 기반 좌석 상태 보정 버전|
|에뮬레이터|Pleos Connect v2 계열, Android 14 (API 34)|
|화면|가로형, 2560 × 1440 px |

에뮬레이터의 세부 버전과 정확한 해상도는 AVD 설정에서 확인이 필요합니다.

테스트는 P단·속도 0에서 시작합니다. 문을 열었다 닫은 뒤 5초 안에 좌석을 Occupied로 바꾸고 3초 이상 유지하면 탑승 감지를 확인할 수 있습니다. 하차는 문 개폐 후 좌석을 Empty로 바꿔 확인합니다.

오류 발생시 페이지 밑에 존재하는 이번 운행 기록 삭제 버튼을 눌러 삭제 후 다시 시작합니다.
