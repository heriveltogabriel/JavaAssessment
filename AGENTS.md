# Orientações de entrega

Ao preparar ou publicar uma nova versão do dashboard, incluir um alerta explícito para atualizar também os agentes nas notas e na mensagem de entrega. Informar, após verificar a compatibilidade dos pacotes, quando a atualização pelo dashboard é possível e quando o instalador completo é necessário. Atualizar o dashboard não atualiza os agentes automaticamente.

Seguir [o processo de release](docs/RELEASE_PROCESS.md). O usuário retomou a evolução do atualizador completo, com compatibilidade mínima 1.5.12 e assessment opcional após confirmar a versão final. Seguir [o desenho](docs/superpowers/specs/2026-10-14-full-agent-update-design.md) e [o plano por workflows](docs/superpowers/plans/2026-10-14-full-agent-update.md). A primeira prova bloqueadora é a migração com o atualizador original 1.5.12; não declarar essa compatibilidade validada antes dos testes definidos. O estado atual está em [docs/TODO.md](docs/TODO.md).
