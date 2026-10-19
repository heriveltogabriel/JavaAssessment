#!/usr/bin/env python3
"""Build the illustrated client manual, preserving released product packages."""
from pathlib import Path
import os
import json
import re
import shutil
import hashlib
from reportlab.pdfgen import canvas
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4, landscape
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from PIL import Image
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]
ASSETS = Path(__file__).parent / 'assets'
OUT = ROOT / 'output/pdf/Manual.pdf'
DELIVERY = ROOT / 'install'
QA = ROOT / 'tmp/pdfs/documentation/manual'
FONTS = Path(os.environ.get('ASSESSMENT_PDF_FONTS', str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/native/libreoffice-headless/libreoffice/LibreOfficeDev.app/Contents/Resources/fonts/truetype')))
for name, file in [('Text', 'NotoSans-Regular.ttf'), ('TextBold', 'NotoSans-Bold.ttf'), ('Display', 'Rubik-Regular.ttf'), ('DisplayBold', 'Rubik-Bold.ttf')]:
    pdfmetrics.registerFont(TTFont(name, str(FONTS/file)))
pdfmetrics.registerFontFamily('Text', normal='Text', bold='TextBold')
W, H = landscape(A4)
M = 40
CW = W - 2*M
GAP = 26
COL = (CW-GAP)/2
GREEN = '#294C3C'
INK = '#24342B'
MUTED = '#657367'
PALE = '#EEF3E8'
CREAM = '#FAFBF7'
LINE = '#D7E1D1'
SAGE = '#A6B69A'
AMBER = '#856423'
TOTAL = 16
for folder in [OUT.parent, DELIVERY, QA]:
    folder.mkdir(parents=True, exist_ok=True)
c = canvas.Canvas(str(OUT), pagesize=(W,H), pageCompression=1)
c.setTitle('Java Assessment | Manual ilustrado da solução')
c.setAuthor('Java Assessment')
c.setSubject('Arquitetura, descoberta, segurança, atualização e operação na infraestrutura do cliente')
c.setKeywords('Java Assessment, inventário, agente, dashboard, JDBC, manual')
text_records = []
bounds = []
image_records = []
PAGE = 0

def color(value):
    return colors.HexColor(value)

def box(x,y,w,h,fill=PALE,stroke=None,radius=9):
    c.setFillColor(color(fill))
    c.setStrokeColor(color(stroke or fill))
    c.setLineWidth(0.6)
    c.roundRect(x,y,w,h,radius,fill=1,stroke=bool(stroke))

def rule(x,y,w= CW,stroke=LINE):
    c.setStrokeColor(color(stroke)); c.setLineWidth(0.6); c.line(x,y,x+w,y)

def text(txt,x,top,w,size=11,leading=16,fill=INK,font='Text',max_h=None):
    style = ParagraphStyle('inline', fontName=font, fontSize=size, leading=leading, textColor=color(fill), splitLongWords=False, allowWidows=0, allowOrphans=0)
    p = Paragraph(txt,style)
    pw,ph = p.wrap(w,1000)
    if max_h is not None:
        assert ph <= max_h+0.1, (PAGE, txt[:70], ph,max_h)
    assert x>=M-0.1 and x+w<=W-M+0.1, (PAGE,txt[:50],x,w)
    assert top-ph>=47, (PAGE,txt[:70],top,ph)
    p.drawOn(c,x,top-ph)
    text_records.append({'page':PAGE,'text':txt})
    bounds.append({'page':PAGE,'x':x,'top':top,'w':w,'h':ph})
    return top-ph

def label(txt,x,y,fill=MUTED,size=8):
    c.setFont('TextBold',size);c.setFillColor(color(fill));c.drawString(x,y,txt)

def footer(section):
    rule(M,39)
    label('JAVA ASSESSMENT  /  MANUAL DA SOLUÇÃO',M,23,size=7)
    c.setFillColor(color(MUTED));c.setFont('Text',7.3);c.drawCentredString(W/2,23,section)
    c.drawRightString(W-M,23,f'{PAGE:02d} / {TOTAL:02d}')

def page(section,key=None,outline=None,level=0):
    global PAGE
    if PAGE:
        c.showPage()
    PAGE+=1
    c.setFillColor(color(CREAM));c.rect(0,0,W,H,fill=1,stroke=0)
    label('JAVA ASSESSMENT',M,H-29,fill=GREEN,size=8.2)
    c.setFont('Text',8);c.setFillColor(color(MUTED));c.drawRightString(W-M,H-29,'MANUAL ILUSTRADO DA SOLUÇÃO')
    rule(M,H-40)
    footer(section)
    if key:
        c.bookmarkPage(key);c.addOutlineEntry(outline or section,key,level=level)

def heading(kicker,title,subtitle):
    label(kicker,M, H-65,fill=MUTED,size=8)
    bottom=text(title,M,H-80,CW,size=26,leading=31,fill=GREEN,font='DisplayBold',max_h=62)
    return text(subtitle,M,bottom-10,CW,size=11,leading=16,fill=MUTED,max_h=32)-21

def note(title,body,y,h=66,fill=PALE):
    box(M,y,CW,h,fill=fill,stroke=LINE)
    text(title,M+15,y+h-11,CW-30,size=10.5,leading=14,fill=GREEN,font='TextBold',max_h=16)
    text(body,M+15,y+h-29,CW-30,size=9.7,leading=14,fill=INK,max_h=h-36)

def sub(title,body,x,top,w=COL):
    bottom=text(title,x,top,w,size=13,leading=17,font='DisplayBold',fill=GREEN)
    return text(body,x,bottom-7,w,size=10.7,leading=15.7)-17

def numbered(n,title,body,x,top,w=COL):
    box(x,top-26,27,27,fill=PALE,radius=8)
    c.setFillColor(color(GREEN));c.setFont('DisplayBold',10);c.drawCentredString(x+13.5,top-18,f'{n:02d}')
    text(title,x+39,top,w-39,size=12.2,leading=16,fill=GREEN,font='TextBold')
    bottom=text(body,x+39,top-23,w-39,size=10.5,leading=15.3)
    return bottom-20

def figure(section,key,title,file,caption):
    page(section,key,title)
    label(section.upper(),M,H-60,fill=GREEN,size=8.2)
    p=ASSETS/file
    with Image.open(p) as im:
        iw,ih=im.size
    width=CW; height=width*ih/iw; top=H-75; bottom=top-height
    assert bottom>80
    c.drawImage(str(p),M,bottom,width=width,height=height,mask='auto')
    image_records.append({'page':PAGE,'filename':file,'width':iw,'height':ih,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
    text(caption,M,bottom-11,CW,size=8.8,leading=12.5,fill=MUTED,max_h=27)

# 01 / Cover, scope and clickable reading route.
page('Apresentação','cover','Apresentação')
label('FUNCIONAMENTO E OPERAÇÃO',M,H-77,fill=MUTED,size=9)
text('Java<br/>Assessment',M,H-101,440,size=48,leading=51,font='DisplayBold',fill=GREEN)
text('Conheça o seu ambiente Java.',M,H-220,445,size=20,leading=25,font='Display',fill=GREEN)
text('Uma solução de descoberta e inventário instalada na infraestrutura do cliente. Reúne máquinas, instalações Java, aplicações, servidores de aplicação, bibliotecas e datasources JDBC em um painel central.',M,H-264,440,size=12,leading=18,max_h=90)
text('Este manual explica a arquitetura, o fluxo da coleta e o uso das informações para planejar atualizações, padronização e modernização.',M,H-367,440,size=11,leading=17,max_h=60)
box(M,86,440,51,fill=PALE,stroke=LINE)
text('Guia de referência',M+14,121,150,size=11,leading=15,font='TextBold',fill=GREEN)
text('19 de outubro de 2026',M+14,103,240,size=9,leading=13,fill=MUTED)
rx=M+469;rw=CW-469
box(rx,86,rw,428,fill=GREEN,radius=13)
text('Explore o manual',rx+20,491,rw-40,size=18,leading=23,font='DisplayBold',fill='#FFFFFF')
text('Clique em um tema para navegar.',rx+20,458,rw-40,size=9.7,leading=15,fill='#DFE9DA')
chapters=[('01','Arquitetura da solução','architecture',2),('02','Da instalação ao inventário','flow',4),('03','Descoberta e evidências','discovery',6),('04','Segurança e acesso','security',8),('05','Atualização de agentes','updates',10),('06','Operação do dia a dia','operations',12),('07','Relatórios e JDBC','reports',13),('08','Planejar a adoção','adoption',15),('09','Atualizar pela console','dashboard-update',16)]
for i,(num,title,key,pno) in enumerate(chapters):
    y=414-i*34
    text(num,rx+20,y,24,size=9.5,leading=14,fill='#BDCFB4',font='TextBold')
    text(title,rx+49,y,rw-86,size=10.1,leading=14.5,fill='#FFFFFF',max_h=30)
    c.setFont('Text',9);c.setFillColor(color('#BDCFB4'));c.drawRightString(rx+rw-17,y-12,str(pno).zfill(2))
    c.linkRect('',key,(rx+15,y-28,rx+rw-15,y+5),relative=0,thickness=0)

# 02 / Architecture visual.
figure('01  /  Arquitetura da solução','architecture','Arquitetura da solução','java-assessment-arquitetura.png','Figura 1. Os agentes iniciam a comunicação HTTPS com o dashboard. O banco SQLite permanece junto da aplicação, na infraestrutura do cliente.')

# 03 / Components and purpose.
page('Arquitetura / Componentes')
top=heading('01  /  COMO A SOLUÇÃO SE ORGANIZA','Três componentes. Uma visão central.','O inventário reúne evidências técnicas e as organiza por ambiente, máquina e recurso descoberto.')
cardw=(CW-28)/3
components=[
    ('01','Dashboard central','Interface web para consultar o inventário, organizar ambientes, solicitar coletas e acompanhar Solicitações.','A equipe também administra contas, credenciais e agentes pelo painel.'),
    ('02','Agente coletor','Instalado em cada máquina Linux, observa processos e inspeciona arquivos e metadados dentro do escopo autorizado.','Consulta tarefas e envia resultados por HTTPS, usando uma identidade própria.'),
    ('03','Banco local SQLite','Armazena inventário, configurações, identidades e histórico operacional no servidor do dashboard.','Dispensa um serviço de banco separado. Backup e proteção do servidor fazem parte da operação local.'),
]
for i,(n,t,a,b) in enumerate(components):
    x=M+i*(cardw+14)
    box(x,233,cardw,193,fill='#FFFFFF',stroke=LINE)
    label(n,x+16,404,fill=MUTED)
    text(t,x+16,389,cardw-32,size=15,leading=20,font='DisplayBold',fill=GREEN)
    text(a,x+16,358,cardw-32,size=10.7,leading=15.7,max_h=81)
    text(b,x+16,284,cardw-32,size=9.4,leading=14,fill=MUTED,max_h=45)
sub('Perguntas que o inventário ajuda a responder','Quais versões de Java existem? Onde são usadas? Quais aplicações e servidores estão associados a elas? Como esses recursos estão distribuídos entre Desenvolvimento, Homologação e Produção?',M,204)
sub('Como transformar a informação em decisão','Use versões, caminhos e vínculos observados para priorizar análises, organizar a padronização e preparar planos de atualização. A decisão de migrar exige também validar a compatibilidade das aplicações.',M+COL+GAP,204)

# 04 / Journey visual.
figure('02  /  Da instalação ao inventário','flow','Da instalação ao inventário','02-fluxo-instalacao.png','Figura 2. O registro ocorre durante a instalação. O contato operacional pode ser confirmado antes de o primeiro inventário chegar ao dashboard.')

# 05 / Journey explanation.
page('Implantação / Primeira coleta')
top=heading('02  /  O CAMINHO ATÉ A PRIMEIRA COLETA','Do ambiente à informação disponível','O dashboard do cliente gera os pacotes dos seus ambientes. Cada máquina mantém seu próprio cadastro.')
steps=[
    ('Criar o ambiente','Organize os grupos operacionais, como Desenvolvimento, Homologação e Produção, e defina o escopo da coleta.'),
    ('Baixar o agente','O ZIP identifica ambiente e versão. Inclui endereço do dashboard, CA pública e autorização temporária de cadastro.'),
    ('Instalar na máquina Linux','Extraia o ZIP em uma pasta adequada e use o instalador com --system-root. O destino operacional é fixo: /opt/java-assessment-collector.'),
    ('Confirmar o cadastro','A instalação registra a máquina e obtém sua credencial individual. O nome do usuário do sistema não precisa ser opc.'),
    ('Solicitar a coleta','O assessment pode ser solicitado ou agendado. Se a coleta inicial foi autorizada no pacote, acompanhe o pedido criado.'),
    ('Consultar o resultado','Abra Solicitações, confira o término da coleta e leia eventuais limitações antes de interpretar as contagens do inventário.'),
]
for i,(title,body) in enumerate(steps):
    col=i%2;row=i//2;x=M+col*(COL+GAP);y=429-row*105
    numbered(i+1,title,body,x,y)
note('Contato não é inventário','O heartbeat confirma comunicação. Sem tarefa, o agente não inicia uma nova varredura. A coleta tem seu próprio resultado e horário.',62,h=61)

# 06 / Inventory visual.
figure('03  /  Descoberta e evidências','discovery','Descoberta e evidências','03-descoberta-inventario.png','Figura 3. As relações representam evidências de inventário. Um arquivo, uma configuração e um processo observado descrevem fatos diferentes.')

# 07 / Evidence explanation.
page('Inventário / Interpretação')
heading('03  /  O SIGNIFICADO DOS DADOS','Entenda o que foi encontrado','A coleta combina evidências disponíveis na máquina. A origem e a cobertura ajudam a interpretar cada recurso.')
widths=[143,285,CW-428]
headers=['RECURSO','O QUE O INVENTÁRIO INFORMA','COMO INTERPRETAR']
y=436
box(M,y-31,CW,31,fill=GREEN,radius=5)
x=M
for w,h in zip(widths,headers):
    text(h,x+11,y-9,w-22,size=8.1,leading=11,fill='#FFFFFF',font='TextBold');x+=w
rows=[
 ('Máquinas','Nome, sistema operacional, arquitetura e ambiente.','Contexto para localizar e organizar os recursos.'),
 ('Instalações Java','Versão, fabricante, caminho e associações de uso.','Inclui JDKs e runtimes sem compilador quando há evidência de uso por aplicação.'),
 ('Aplicações','Processos Java e artefatos JAR, WAR e EAR.','Arquivo encontrado não comprova implantação nem saúde da aplicação.'),
 ('Servidores e bibliotecas','Produto e versão de Tomcat, JBoss/WildFly e WebLogic; frameworks e bibliotecas.','A cobertura depende do produto, formato e evidência disponíveis.'),
 ('Datasources JDBC','Datasource, driver, versão, classe, banco e vínculos disponíveis.','Driver presente não equivale a datasource. Configuração não comprova conexão ativa.'),
 ('Evidências e horários','Fonte da informação, observação do processo e data da coleta.','O resultado descreve o que foi observado naquele momento.'),
]
y-=31
for idx,row in enumerate(rows):
    rh=45
    c.setFillColor(color('#FFFFFF' if idx%2==0 else PALE));c.rect(M,y-rh,CW,rh,fill=1,stroke=0)
    x=M
    for j,(w,txt) in enumerate(zip(widths,row)):
        text(txt,x+11,y-9,w-22,size=9.4,leading=13.2,font='TextBold' if j==0 else 'Text',fill=GREEN if j==0 else INK,max_h=rh-14);x+=w
    rule(M,y-rh);y-=rh
note('Cobertura JDBC','Há descoberta por configuração em Spring Boot, Tomcat, JBoss/WildFly standalone e WebLogic, dentro dos formatos suportados. Arquivos externos, perfis e sobreposições podem ficar fora da cobertura.',61,h=64)

# 08 / Security visual.
figure('04  /  Segurança e acesso','security','Segurança e acesso','04-seguranca-controle.png','Figura 4. Comunicação HTTPS, identidade individual e execução com escopo definido formam a base dos controles da solução.')

# 09 / Security explanation.
page('Segurança / Pessoas e agentes')
heading('04  /  QUEM ACESSA E QUEM OPERA','Contas individuais e operação controlada','As credenciais das pessoas e as credenciais dos agentes têm funções separadas.')
left=M;right=M+COL+GAP
text('Perfis da equipe',left,433,COL,size=15,leading=20,font='DisplayBold',fill=GREEN)
box(left,316,COL,88,fill=PALE,stroke=LINE)
text('Administrador',left+14,390,COL-28,size=12,leading=16,font='TextBold',fill=GREEN)
text('Consulta o inventário e gerencia usuários, ambientes, coletas, agentes, autorizações e atualizações.',left+14,366,COL-28,size=10.7,leading=15.7,max_h=48)
box(left,211,COL,88,fill='#FFFFFF',stroke=LINE)
text('Leitura',left+14,285,COL-28,size=12,leading=16,font='TextBold',fill=GREEN)
text('Consulta o inventário e os relatórios, acompanha Solicitações e exporta PDF/CSV. Não administra o ambiente.',left+14,261,COL-28,size=10.7,leading=15.7,max_h=48)
text('<b>Alcance dos perfis:</b> ambos consultam todos os ambientes. O filtro por ambiente organiza a visualização; ele não restringe a autorização de acesso.',left,194,COL,size=10.3,leading=15,max_h=65)
y=433
y=sub('Primeiro acesso e administração','Cada pessoa entra com email e senha. A senha temporária deve ser trocada no primeiro acesso. O administrador cria contas, define o perfil e pode desativar acessos.',right,y)
y=sub('Comunicação e identidade','Os agentes iniciam conexões HTTPS e validam o servidor. Após o cadastro, usam uma credencial própria, revogável. A autorização temporária do pacote serve para novos registros.',right,y)
y=sub('Coleta dentro do escopo','Caminhos autorizados, exclusões e limites de tempo e leitura delimitam a coleta. A descoberta de JDBC não abre conexão com o banco nem executa classes encontradas.',right,y)
note('Responsabilidade operacional','A equipe do cliente administra o servidor, os certificados, os acessos e os backups. Os dados ficam na infraestrutura em que a solução foi instalada.',62,h=61)

# 10 / Full update flow, drawn as selectable PDF content.
page('05  /  Atualização de agentes','updates','Atualização de agentes')
heading('05  /  ATUALIZAÇÃO COMPLETA','Evoluir pelo painel. Confirmar por máquina.','O agente consulta o pedido por HTTPS. O dashboard não precisa de chave SSH para atualizar instalações elegíveis.')
steps=[('01','Revisar','Confira versão, suporte e elegibilidade. Selecione uma máquina ou um ambiente.'),('02','Verificar','O atualizador valida assinatura, integridade e compatibilidade do pacote.'),('03','Aplicar','Instala os componentes previstos e preserva cadastro, configuração e confiança HTTPS.'),('04','Confirmar','Verifica o funcionamento. O dashboard registra a versão final e o resultado.')]
cw=(CW-42)/4
for i,(n,t,b) in enumerate(steps):
    x=M+i*(cw+14)
    box(x,260,cw,165,fill='#FFFFFF',stroke=LINE)
    label(n,x+14,401,fill=MUTED,size=11)
    text(t,x+14,382,cw-28,size=17,leading=22,font='DisplayBold',fill=GREEN)
    text(b,x+14,346,cw-28,size=10.4,leading=15.5,max_h=78)
for i,(title,body,fill,accent) in enumerate([
    ('Concluído','A versão final foi confirmada. A coleta posterior tem resultado próprio.',PALE,GREEN),
    ('Sem confirmação','O resultado ainda é desconhecido. Acompanhe o mesmo pedido.','#F8F3E5',AMBER),
    ('Falha / restauração','Leia o motivo e a confirmação da restauração antes de tentar novamente.','#F8EEEA','#865441')]):
    w=(CW-28)/3;x=M+i*(w+14)
    box(x,142,w,96,fill=fill)
    text(title,x+13,225,w-26,size=12,leading=17,font='TextBold',fill=accent)
    text(body,x+13,199,w-26,size=10,leading=14,max_h=47)
note('Assessment após a atualização','Mantenha a opção habilitada para solicitar a coleta após a confirmação. Acompanhe a atualização e abra o assessment vinculado no mesma Solicitação.',61,h=65)

# 11 / Update explanation.
page('Atualização / Solicitar e confirmar')
heading('05  /  COMO ATUALIZAR PELO DASHBOARD','Solicitar, verificar e acompanhar','O administrador inicia o pedido. O agente o recebe na próxima conexão HTTPS, sem chave SSH para esse fluxo.')
left=M;right=M+COL+GAP
y=431
for n,t,b in [
 (1,'Selecionar o ambiente','Na aba Máquinas, use Atualizar agentes do ambiente para abrir a seleção de várias máquinas.'),
 (2,'Revisar a elegibilidade','Confira versão instalada, versão disponível e motivos de impedimento. Marque máquinas ou selecione as elegíveis.'),
 (3,'Confirmar o lote','Cada máquina tem 5 minutos para iniciar na sua vez. Sem resposta, o lote segue. Quando ela voltar, crie um novo pedido.'),
 (4,'Acompanhar a confirmação','Ao confirmar, abre a Solicitação do pedido. Veja a versão final e a coleta vinculada. Fechar a tela não cancela a operação.'),
]:
    y=numbered(n,t,b,left,y)
text('O que cada resultado significa',right,431,COL,size=15,leading=20,font='DisplayBold',fill=GREEN)
y=399
for title,body,fill,accent in [
 ('Confirmado','O dashboard recebeu as confirmações exigidas e registra a conclusão.',PALE,GREEN),
 ('Aguardando confirmação','O resultado ainda é desconhecido. O estado é preservado para tentar confirmar novamente; o lote aguarda.','#F8F3E5',AMBER),
 ('Versão anterior restaurada','Uma falha do novo código acionou a restauração. Leia o motivo antes de planejar outra tentativa.','#F8EEEA','#865441'),
]:
    box(right,y-78,COL,78,fill=fill)
    text(title,right+13,y-11,COL-26,size=11.2,leading=15,font='TextBold',fill=accent)
    text(body,right+13,y-33,COL-26,size=10.2,leading=14.4,max_h=43.5)
    y-=88
note('Atenção: atualize também os agentes','Publicar o dashboard não atualiza as máquinas. V3 e perfis legados conhecidos usam o painel quando elegíveis. Sem suporte elegível, use o pacote do ambiente com --system-root. A versão sozinha não comprova compatibilidade.',61,h=65)

# 12 / Practical usage and reports.
page('Operação / Consulta e acompanhamento','operations','Operação do dia a dia')
heading('06  /  ROTINA DA EQUIPE','Do pedido de coleta à análise','Use o contexto do ambiente, a data da informação e os detalhes do pedido para avaliar o resultado.')
left=M;right=M+COL+GAP
y=431
for n,t,b in [
 (1,'Selecionar o contexto','Use Java Assessment para abrir Machines em Todos os ambientes, ou selecione um ambiente e uma máquina.'),
 (2,'Solicitar uma coleta atual','Após instalar, alterar ou atualizar uma aplicação, solicite um novo assessment para renovar as evidências.'),
 (3,'Acompanhar Solicitações','Use o menu lateral. O botão Filtros abre ou recolhe ambiente, tipo, estado e período, mantendo os critérios aplicados.'),
 (4,'Exportar para análise','Em Relatórios, escolha modelo, filtros e colunas para PDF/CSV. Actions mantém a exportação única do inventário.'),
]:
    y=numbered(n,t,b,left,y)
y=431
y=sub('Resultado recebido','A coleta tem horário e conclusão próprios. O registro da máquina e o último contato do agente não substituem essa confirmação.',right,y)
y=sub('Concluído com observações','O inventário foi recebido. Abra Observações para identificar caminhos inacessíveis, limites alcançados ou formatos não cobertos.',right,y)
y=sub('Exportação única em CSV','O CSV de Actions reúne registros do escopo escolhido. Já PDF/CSV de Relatórios respeitam os filtros e colunas da consulta, incluindo todas as páginas.',right,y)
y=sub('Se um recurso esperado não aparecer','Confira processo, configuração, escopo, permissões e avisos de cobertura. Depois de ajustar a causa, solicite outro assessment.',right,y)
note('Atualizar a tela e executar um assessment são ações diferentes','Recarregar o painel mostra os dados já recebidos. Uma nova coleta precisa ser solicitada ou estar prevista em um agendamento autorizado.',61,h=61)

# 13-14 / Report models and JDBC filters.
page('Relatórios / Modelos e colunas','reports','Relatórios e JDBC')
heading('07  /  RELATÓRIOS','Do inventário ao resumo para decisão','Escolha o modelo, combine filtros e selecione as colunas. Todos os modelos incluem informações JDBC.')
rows=[('Máquinas','Ambiente, IP, sistema operacional, versões Java, servidores e JDBC da máquina.'),('Java','Máquina, ambiente, IP, versão, Java home, fornecedor e JDBC da máquina.'),('Servidores Java','Máquina, ambiente, IP, produto, nome e versão do servidor, Java associado, SO e JDBC do servidor.'),('JDBC','Datasource/nome JNDI, banco, driver, versão, classe, máquina, ambiente, IP e associações.'),('Ambientes','Quantidades de máquinas, instalações Java, servidores e datasources, com resumo JDBC.')]
y=429
for i,(title,body) in enumerate(rows):
    box(M,y-53,CW,53,fill='#FFFFFF' if i%2==0 else PALE,stroke=LINE,radius=5)
    text(title,M+13,y-13,126,size=11.5,leading=16,font='TextBold',fill=GREEN)
    text(body,M+154,y-12,CW-169,size=10.6,leading=15,max_h=32)
    y-=60
note('Mais detalhes quando necessários','Em Colunas, adicione caminho, PID, Java home ou JDBC da máquina no modelo de servidores. Em JDBC, adicione JAR e versão do driver. A versão do banco exige evidência própria; um JAR não a comprova.',61,h=65)

page('Relatórios / Filtros, vínculos e exportação')
heading('07  /  JDBC E EXPORTAÇÕES','Filtrar com contexto. Compartilhar com clareza.','As sugestões vêm do inventário e também permitem digitação. Recarregar atualiza os dados disponíveis.')
left=M;right=M+COL+GAP
y=431
y=sub('Filtros de máquinas e servidores','Selecione ambiente, máquina, sistema operacional, Java e servidor conforme o modelo. Exemplo: máquinas com WebLogic de uma versão específica.',left,y)
y=sub('Filtros JDBC','Combine presença de JDBC, datasource/nome JNDI, banco, driver e versão, classe, referência, servidor, aplicação, tipo de recurso e estado.',left,y)
y=sub('Critérios no mesmo datasource','Ao combinar banco, driver e aplicação, os critérios precisam corresponder ao mesmo datasource. Em Servidores Java, o recurso precisa estar associado ao servidor da linha.',left,y)
y=431
y=sub('Vínculos que o inventário comprova','JDBC do servidor usa associações registradas; estar na mesma máquina não basta. JDBC da máquina mostra o contexto do host. A versão Java do servidor vem do Java home correspondente.',right,y)
y=sub('PDF e CSV','As exportações usam as colunas e os filtros escolhidos, com todas as páginas. Limites: 10.000 registros e 8.192 caracteres por célula. Refine o escopo quando necessário.',right,y)
y=sub('O que não aparece no relatório','Não são incluídos data da coleta, cobertura, URLs JDBC, senhas ou tokens. O PDF informa a data de geração. Campos sem evidência permanecem sem informação.',right,y)
note('Datasource, driver e versão permanecem juntos','A configuração encontrada não confirma conexão ativa com o banco. Um driver presente na máquina não prova que ele seja usado por todos os datasources.',61,h=65)

# 13 / Adoption boundaries and sizing.
page('Adoção / Compatibilidade e implantação','adoption','Planejar a adoção')
heading('08  /  PREPARAÇÃO PARA O CLIENTE','Implantar com contexto e validação','Dashboard e agentes têm requisitos diferentes. A expansão deve considerar as famílias de sistemas e aplicações do cliente.')
left=M;right=M+COL+GAP
y=431
y=sub('Servidor do dashboard','O instalador fornecido é destinado a Linux Enterprise 8+ compatível, 64 bits, com systemd e JDK 17 ou superior. O banco SQLite fica local ao dashboard.',left,y)
y=sub('Memória e infraestrutura','A instalação nova configura heap inicial e máximo de 1,5 GiB e limite de 2 GiB para o serviço. Uma VM dedicada de 4 GiB é referência inicial, sujeita ao volume real, ao sistema e à retenção.',left,y)
y=sub('Agentes nas máquinas Linux','O agente utiliza uma JVM existente e compatível; não exige compilador. A instalação como serviço usa uma JVM protegida. O heap do coletor vai de 16 MiB a 96 MiB; memória nativa e subprocessos são adicionais.',left,y)
y=431
y=sub('Java e Linux legados','O coletor é compilado para bytecode compatível com Java 6. Isso não certifica todas as combinações de sistema, JVM e arquitetura. Linux/JVM de 32 bits não têm validação suficiente. Homologue cada plataforma do cliente, inclusive as legadas.',right,y)
y=sub('Impacto e cobertura','O custo depende dos caminhos, arquivos, disco e limites configurados. Meça tempo, memória e cobertura em uma amostra representativa antes de ampliar. Os testes locais não certificam todo ambiente de produção.',right,y)
y=sub('O papel da solução','O inventário apoia decisões de atualização e modernização. Não substitui testes de compatibilidade, validação funcional das aplicações ou monitoramento contínuo de desempenho e disponibilidade.',right,y)
note('Comece por uma amostra representativa','Instale, confirme cadastro e coleta, compare recursos conhecidos e avalie cobertura e consumo. Use o Guia de instalação para os comandos e a validação operacional.',61,h=65)

page('Administração / Atualização do dashboard','dashboard-update','Atualizar pela console')
heading('09  /  ATUALIZAÇÃO DO DASHBOARD','Enviar, revisar e confirmar','Requer o executor independente instalado e um pacote assinado compatível com a origem. O processo continua mesmo se o navegador fechar.')
left=M;right=M+COL+GAP
y=431
for n,t,b in [
 (1,'Enviar o pacote web','Em Administração, abra Atualização do dashboard. Envie assessment-dashboard-web-update-X.Y.Z.zip. O instalador de servidor novo não serve para upload.'),
 (2,'Revisar a preparação','Aguarde a assinatura, origem, espaço e operações pendentes serem verificados. Confira versões e notas; você pode cancelar antes da aplicação.'),
 (3,'Atualizar e reiniciar','Informe sua senha administrativa e confirme. O executor cria backup consistente, aplica os arquivos e reinicia o dashboard.'),
]:
    y=numbered(n,t,b,left,y)
y=431
y=sub('Conferir o resultado','Aguarde o serviço voltar. Se a sessão terminar, faça login e reabra a atualização. Confira versão, conclusão e log, depois consulte máquinas e relatórios.',right,y)
y=sub('Falha ou restauração','Uma falha na aplicação aciona tentativa automática de voltar ao código anterior. A última atualização concluída oferece restauração compatível, com senha e nova confirmação.',right,y)
y=sub('Banco e agentes','A restauração preserva o banco atual. Atualizar o dashboard não atualiza agentes: revise elegibilidade e solicite a atualização separadamente, começando por uma máquina.',right,y)
note('Quando usar acesso ao servidor','Sem executor independente, faça a primeira adoção pelo script compatível da entrega. Se o serviço não voltar e a recuperação falhar, consulte o log do executor via SSH. O navegador não recupera um serviço que não consegue iniciar.',61,h=65)

assert PAGE==TOTAL, PAGE
c.save()
reader=PdfReader(str(OUT))
assert len(reader.pages)==TOTAL
plain='\n\n'.join(p.extract_text() or '' for p in reader.pages)
assert '\ufffd' not in plain
assert not re.search(r'\b(?:TODO|TBD|Lorem ipsum)\b',plain)
assert len(image_records)==4
normalized=' '.join(plain.split())
for phrase in ['Java Assessment','SQLite','Solicitações','Concluído com observações','1,5 GiB','96 MiB','32 bits','Relatórios']:
    assert phrase in normalized, phrase
assert sum(len(p.images) for p in reader.pages)==4
for folder in [DELIVERY]:
    shutil.copy2(OUT,folder/OUT.name)
    assert (folder/OUT.name).read_bytes()==OUT.read_bytes()
(QA/'manual-texto.txt').write_text(plain,encoding='utf-8')
(QA/'layout-bounds.json').write_text(json.dumps(bounds,ensure_ascii=False,indent=2),encoding='utf-8')
report={'pdf':str(DELIVERY/OUT.name),'pages':TOTAL,'page_size':'A4 landscape','images':image_records,'sha256':hashlib.sha256(OUT.read_bytes()).hexdigest(),'bytes':OUT.stat().st_size,'text_checks':'passed','visual_review':'pending','copies':[str(OUT),str(DELIVERY/OUT.name)]}
(QA/'manual-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
