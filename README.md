# Pixel People (NeoForge 1.21.1)

Люди из пикселей-кубиков с собственным рэгдолом. Яйцо призыва: вкладка «Spawn Eggs» → Pixel Person.

## Сборка
Нужны Java 21 и Gradle 8.10+ (или обёртка gradlew из официального MDK NeoForge 1.21.1).

    gradle build          # jar появится в build/libs/pixelpeople-1.0.0.jar
    gradle runClient      # запуск игры для теста

Если gradlew нет: скачай MDK 1.21.1 с github.com/NeoForgeMDK, удали из него src/ и
скопируй вместо него папку src/ из этого архива (и build.gradle / gradle.properties оттуда же).

## Как подменить скин
Положи свой скин 64x64 (формат с отдельными левой рукой и ногой) в
src/main/resources/assets/pixelpeople/textures/entity/pixel_person.png

## Файлы
PixelModel   — скин разрезан на ~1170 кубиков-пикселей
PixelPose    — позы: ходьба, рэгдол-лежание, вставание
PixelPerson  — сущность: ИИ, характер, боль, падение/вставание, отваливание пикселей
PixelPersonRenderer — отрисовка кубиков и летящих пикселей
