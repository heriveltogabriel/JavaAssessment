# Java 17 para instalação sem download na VM

Pacote enviado pelo mantenedor: **Eclipse Temurin (OpenJDK) 17.0.20.1+1**, Linux x86_64, HotSpot, JDK completo. Este pacote não é Oracle JDK e não é para ARM ou Linux de 32 bits.

O ZIP original foi dividido em três partes devido ao [limite de tamanho do GitHub](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github). Seu conteúdo foi preservado, incluindo os arquivos de licença do JDK. Os hashes permitem conferir a cópia; não substituem a verificação de procedência junto ao fornecedor.

## Baixar e reconstruir

Baixe as três partes e os arquivos de checksum para a mesma pasta, ou clone o repositório e entre em `install/java`:

- [Parte 1](OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part01)
- [Parte 2](OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part02)
- [Parte 3](OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part03)
- [Checksums das partes](SHA256SUMS.parts)
- [Checksum do ZIP original](OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.sha256)

No Linux:

```sh
sha256sum -c SHA256SUMS.parts &&
cat OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part01 OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part02 OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.part03 > OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip &&
sha256sum -c OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip.sha256
```

No macOS, use `shasum -a 256 -c` no lugar de `sha256sum -c`.

## Extrair na VM Linux

Depois de confirmar os checksums:

```sh
unzip OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.zip 'OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz' &&
sudo mkdir -p /opt/java &&
sudo tar -xzf OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz -C /opt/java
/opt/java/jdk-17.0.20.1+1/bin/java -version
/opt/java/jdk-17.0.20.1+1/bin/javac -version
```

No instalador do dashboard, acrescente `--java-home /opt/java/jdk-17.0.20.1+1 --no-package-install` aos demais parâmetros do guia. O instalador ainda exige Python 3 e OpenSSL previamente instalados para não recorrer ao DNF durante o bootstrap.
