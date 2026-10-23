#!/bin/sh
set -e

# База DB-IP Lite Country скачивается сюда сама, если её ещё нет — вручную
# это уже дважды срывалось: шаг вне docker compose up предсказуемо забывают
# или откладывают. DB-IP Lite (в отличие от MaxMind GeoLite2) не требует
# регистрации и лицензионного ключа, и распространяется в том же формате
# mmdb — читающая библиотека (com.maxmind.db) не различает, кто его собрал.
#
# Публикуется помесячно, обычно в первых числах — если файла за текущий
# месяц ещё нет, пробуем прошлый. Сети при старте может не быть: тогда файл
# остаётся не скачан, CountryResolver уже умеет явно и без падения сообщать
# об этом в лог и работать с определением страны отключённым.
if [ -n "$GEOIP_DB_PATH" ] && [ ! -f "$GEOIP_DB_PATH" ]; then
    mkdir -p "$(dirname "$GEOIP_DB_PATH")"

    YEAR=$(date +%Y)
    MONTH=$((10#$(date +%m)))
    PREV_MONTH=$((MONTH - 1))
    PREV_YEAR=$YEAR
    if [ "$PREV_MONTH" -eq 0 ]; then
        PREV_MONTH=12
        PREV_YEAR=$((YEAR - 1))
    fi
    THIS_MONTH_TAG="${YEAR}-$(printf '%02d' "$MONTH")"
    PREV_MONTH_TAG="${PREV_YEAR}-$(printf '%02d' "$PREV_MONTH")"

    for TAG in "$THIS_MONTH_TAG" "$PREV_MONTH_TAG"; do
        URL="https://download.db-ip.com/free/dbip-country-lite-${TAG}.mmdb.gz"
        echo "geoip: пробую скачать базу за ${TAG} ($URL)"
        if wget -q -T 10 -O "${GEOIP_DB_PATH}.gz" "$URL" && gunzip -f "${GEOIP_DB_PATH}.gz"; then
            echo "geoip: база скачана в ${GEOIP_DB_PATH}"
            break
        fi
        echo "geoip: не удалось скачать базу за ${TAG}"
        rm -f "${GEOIP_DB_PATH}.gz"
    done

    if [ ! -f "$GEOIP_DB_PATH" ]; then
        echo "geoip: база не скачана (нет сети или обе попытки не удались) — определение страны по IP останется отключено, старт продолжается"
    fi
fi

exec java -Xmx512m -XX:+UseG1GC -jar app.jar
