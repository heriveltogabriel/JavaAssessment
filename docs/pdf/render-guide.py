#!/usr/bin/env python3
"""Client installation guide; product packages remain unchanged."""
from pathlib import Path
import os
import html
import json
import shutil
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import BaseDocTemplate, Frame, PageTemplate, Paragraph, Spacer, PageBreak, Table, TableStyle, Flowable

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'output/pdf/Guia-instalacao.pdf'
DELIVERY = ROOT / 'install'
FONTS = Path(os.environ.get('ASSESSMENT_PDF_FONTS', str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/native/libreoffice-headless/libreoffice/LibreOfficeDev.app/Contents/Resources/fonts/truetype')))
for name, file in [('DV','DejaVuSans.ttf'),('DVB','DejaVuSans-Bold.ttf'),('DVM','DejaVuSansMono.ttf')]:
    pdfmetrics.registerFont(TTFont(name,str(FONTS/file)))
pdfmetrics.registerFontFamily('DV',normal='DV',bold='DVB')
W,H=A4; M=50; CW=W-2*M
GREEN=colors.HexColor('#294C3C'); INK=colors.HexColor('#24342B'); MUTED=colors.HexColor('#667268')
PALE=colors.HexColor('#F0F4EC'); LINE=colors.HexColor('#D9E2D5'); DARK=colors.HexColor('#202E27')
BODY=ParagraphStyle('body',fontName='DV',fontSize=9.3,leading=14.5,textColor=INK,spaceAfter=8)
SMALL=ParagraphStyle('small',parent=BODY,fontSize=8.5,leading=12.8,spaceAfter=5)
SUB=ParagraphStyle('sub',fontName='DVB',fontSize=11.3,leading=15,textColor=GREEN,spaceBefore=10,spaceAfter=7,keepWithNext=True)
HEAD=ParagraphStyle('head',fontName='DVB',fontSize=23,leading=29,textColor=GREEN,spaceAfter=12,keepWithNext=True)
KICK=ParagraphStyle('kick',fontName='DVB',fontSize=8,leading=12,textColor=MUTED,spaceAfter=7,keepWithNext=True)
TABLE=ParagraphStyle('table',parent=BODY,fontSize=8.7,leading=13,spaceAfter=0)
TCAP=ParagraphStyle('tcap',parent=TABLE,fontName='DVB',textColor=GREEN)

def p(text,style=BODY): return Paragraph(text,style)
def mono(text): return '<font name="DVM" size="8.4">'+html.escape(text)+'</font>'
def bullet(text): return p('<font color="#6B8F72">•</font>  '+text)
def note(title,text):
    t=Table([[p(title,TCAP)],[p(text,SMALL)]],colWidths=[CW-24])
    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,-1),PALE),('LEFTPADDING',(0,0),(-1,-1),12),('RIGHTPADDING',(0,0),(-1,-1),12),('TOPPADDING',(0,0),(-1,0),10),('BOTTOMPADDING',(0,-1),(-1,-1),9),('TOPPADDING',(0,1),(-1,-1),2),('BOX',(0,0),(-1,-1),0.5,LINE)]))
    t.spaceBefore=3;t.spaceAfter=10;return t

def table(rows,widths=(142,CW-142)):
    t=Table([[p(a,TCAP),p(b,TABLE)] for a,b in rows],colWidths=widths,hAlign='LEFT')
    t.setStyle(TableStyle([('ROWBACKGROUNDS',(0,0),(-1,-1),[PALE,colors.white]),('VALIGN',(0,0),(-1,-1),'TOP'),('LEFTPADDING',(0,0),(-1,-1),10),('RIGHTPADDING',(0,0),(-1,-1),10),('TOPPADDING',(0,0),(-1,-1),9),('BOTTOMPADDING',(0,0),(-1,-1),9),('LINEBELOW',(0,0),(-1,-1),0.4,LINE)]))
    t.spaceAfter=9;return t

commands=[]
class Code(Flowable):
    def __init__(self,label,code):
        Flowable.__init__(self);self.label=label;self.lines=code.strip('\n').splitlines();self.width=CW;self.height=40+12.6*len(self.lines);self.spaceAfter=10;self.spaceBefore=2
        for line in self.lines:
            assert pdfmetrics.stringWidth(line,'DVM',8)<=CW-28, 'Command too wide: '+line
        commands.append({'label':label,'code':'\n'.join(self.lines)})
    def wrap(self,aw,ah):return self.width,self.height
    def draw(self):
        c=self.canv;c.setFillColor(DARK);c.roundRect(0,0,self.width,self.height,7,fill=1,stroke=0)
        c.setFillColor(colors.HexColor('#B3CDB6'));c.setFont('DVB',7.1);c.drawString(13,self.height-17,self.label.upper())
        c.setStrokeColor(colors.HexColor('#3C5244'));c.line(13,self.height-24,self.width-13,self.height-24)
        c.setFillColor(colors.white);c.setFont('DVM',8)
        for i,line in enumerate(self.lines):c.drawString(13,self.height-39-i*12.6,line)

stages=[('01','Preparar o ambiente','Requisitos, rede e arquivos da entrega',2),('02','Instalar o dashboard','Integridade, extração e configuração',3),('03','Configurar o acesso','Primeiro login, ambientes e pacotes',4),('04','Instalar os agentes','Serviço root e Java existente',5),('05','Validar a primeira coleta','Versão, contato e inventário',6),('06','Atualizar e consultar','Solicitações, assessment e relatórios',7),('07','Cuidar da operação','HTTPS, backup e homologação',8)]
class Doc(BaseDocTemplate):
    def __init__(self):
        BaseDocTemplate.__init__(self,str(OUT),pagesize=A4,title='Java Assessment | Guia de instalação do cliente',author='Java Assessment',subject='Dashboard e agentes em ambiente próprio',leftMargin=M,rightMargin=M,topMargin=85,bottomMargin=55)
        frame=Frame(M,55,CW,H-140,leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0)
        self.addPageTemplates(PageTemplate(id='body',frames=frame,onPage=self.decorate))
    def decorate(self,c,doc):
        c.saveState()
        if doc.page==1:self.cover(c)
        else:
            c.setFillColor(GREEN);c.setFont('DVB',8);c.drawString(M,H-39,'JAVA ASSESSMENT')
            c.setFillColor(MUTED);c.setFont('DV',8);c.drawRightString(W-M,H-39,'GUIA DO CLIENTE')
            c.setStrokeColor(LINE);c.line(M,H-56,W-M,H-56);c.line(M,40,W-M,40)
            c.setFont('DV',7.4);c.drawString(M,25,'Instalação nova  •  Dashboard e agentes na rede do cliente')
            c.drawRightString(W-M,25,f'{doc.page:02d} / 08')
        c.restoreState()
    def cover(self,c):
        c.setFillColor(PALE);c.rect(0,0,W,H,fill=1,stroke=0);c.setFillColor(GREEN);c.rect(0,0,12,H,fill=1,stroke=0)
        c.roundRect(M,H-101,41,41,9,fill=1,stroke=0);c.setFillColor(colors.white);c.setFont('DVB',24);c.drawCentredString(M+20.5,H-89,'J')
        c.setFillColor(GREEN);c.setFont('DVB',10);c.drawString(M+55,H-76,'JAVA ASSESSMENT');c.setFont('DV',8.5);c.setFillColor(MUTED);c.drawString(M+55,H-94,'GUIA DE INSTALAÇÃO')
        c.setFillColor(INK);c.setFont('DVB',29);c.drawString(M,H-168,'Do primeiro acesso');c.drawString(M,H-207,'à primeira coleta.')
        c.setFillColor(GREEN);c.roundRect(M,H-247,146,23,11.5,fill=1,stroke=0);c.setFillColor(colors.white);c.setFont('DVB',8.8);c.drawCentredString(M+73,H-239,'GUIA DE REFERÊNCIA')
        c.setFillColor(MUTED);c.setFont('DV',10);c.drawString(M,H-285,'Dashboard e agentes instalados na rede do cliente.');c.drawString(M,H-304,'Etapas objetivas, comandos selecionáveis e validação final.')
        c.setStrokeColor(LINE);c.line(M,H-334,W-M,H-334);c.setFont('DVB',8);c.setFillColor(GREEN);c.drawString(M,H-355,'ROTEIRO  /  CLIQUE NA ETAPA PARA NAVEGAR')
        for i,(num,title,detail,page) in enumerate(stages):
            y=H-393-i*44;c.setFillColor(colors.HexColor('#DFE9DA'));c.roundRect(M,y-17,31,29,7,fill=1,stroke=0)
            c.setFillColor(GREEN);c.setFont('DVB',8.5);c.drawCentredString(M+15.5,y-6,num)
            c.setFont('DVB',10);c.drawString(M+44,y,title);c.setFont('DV',8.3);c.setFillColor(MUTED);c.drawString(M+44,y-15,detail);c.drawRightString(W-M,y-5,f'{page:02d}')
            c.linkRect('',f'stage-{num}',(M,y-20,W-M,y+15),relative=0,thickness=0)
        c.setStrokeColor(LINE);c.line(M,75,W-M,75);c.setFont('DV',8);c.drawString(M,55,'19 de outubro de 2026');c.drawRightString(W-M,55,'HTTPS 443  •  Credenciais individuais')
    def afterFlowable(self,f):
        if hasattr(f,'bookmark'):
            self.canv.bookmarkPage(f.bookmark);self.canv.addOutlineEntry(f.title_text,f.bookmark,level=0)

story=[Spacer(1,1),PageBreak()]
def section(number,title,subtitle):
    if number!='01':story.append(PageBreak())
    story.append(p('ETAPA '+number+'  /  IMPLANTAÇÃO',KICK));h=p(title,HEAD);h.bookmark='stage-'+number;h.title_text=title;story.append(h);story.append(p(subtitle))

def add(*items):story.extend(items)

section('01','Preparar o ambiente','Separe o servidor do dashboard das máquinas monitoradas. As exigências de Java são diferentes para cada função.')
add(table([
 ('Servidor do dashboard','Linux Enterprise 8+ compatível com Oracle Linux/RHEL, 64 bits, '+mono('systemd')+' e '+mono('dnf')+'. Acesso administrativo com root ou sudo.'),
 ('Java do dashboard','<b>JDK 17 ou superior</b>, com '+mono('java')+' e '+mono('javac')+'. Python 3, OpenSSL e unzip também são necessários.'),
 ('Memória do dashboard','Heap inicial e máximo de <b>1,5 GiB</b>; limite do serviço de <b>2 GiB</b>. Uma VM dedicada com <b>4 GiB</b> é um ponto de partida, sujeito ao volume real.'),
 ('Disco e persistência','Espaço em '+mono('/opt')+' para aplicação, banco e logs. O volume deve permitir carregar bibliotecas nativas. Dimensione retenção e backups com a equipe local.'),
 ('Máquinas com agente','Linux com Java existente compatível. <b>JRE/JVM basta; não exige javac.</b> Heap máximo do coletor: <b>96 MiB</b>, além da memória nativa.'),
]),p('Endereço e rede',SUB),
 bullet('Defina DNS ou IP fixo, inclusive privado, que todos os agentes e navegadores consigam alcançar.'),
 bullet('Libere <b>TCP 443</b> dessas origens até o dashboard. Os agentes precisam de saída HTTPS; o dashboard não precisa abrir SSH para coletar.'),
 bullet('Se faltarem dependências, o instalador usa repositórios '+mono('dnf')+'. Em rede isolada, prepare as dependências antes.'),
 p('Arquivos que acompanham a entrega',SUB),
 p(mono('assessment-dashboard-installer-X.Y.Z.zip')+'<br/>'+mono('assessment-dashboard-installer-X.Y.Z.zip.sha256')+'<br/>Este guia de instalação.',SMALL),
 note('Antes de começar','O dashboard exige Linux moderno. Java 6 e Linux antigo pertencem ao cenário dos agentes e precisam de homologação específica; veja a etapa 07.'))

section('02','Instalar o dashboard','Execute no servidor novo do cliente, na pasta que recebeu o ZIP e o arquivo SHA-256. Interrompa a sequência se um comando falhar.')
add(p('1. Conferir a integridade',SUB),Code('Servidor do dashboard - informe a versão baixada', 'VERSAO="X.Y.Z"\nZIP="assessment-dashboard-installer-${VERSAO}.zip"\nsha256sum -c "$ZIP.sha256"'),
 p('Substitua X.Y.Z pela versão do arquivo baixado. Mantenha o ZIP e o SHA-256 com seus nomes originais. O resultado precisa indicar '+mono('OK')+'. Se houver divergência, transfira novamente os arquivos antes de instalar.',SMALL),
 p('2. Extrair em uma pasta nova',SUB),Code('Servidor do dashboard', r'''umask 077
unzip "$ZIP" -d "dashboard-installer-$VERSAO"
cd "dashboard-installer-$VERSAO"
chmod +x install.sh'''),
 p('3. Informar o endereço e o primeiro administrador',SUB),Code('Substitua os três valores de exemplo', r'''./install.sh \
  --public-url https://dashboard.exemplo.com \
  --admin-name "Administrador" \
  --admin-email admin@exemplo.com'''),
 p('Use o DNS/IP, nome e email reais do cliente. '+mono('--public-url')+' aceita endereço privado; o parâmetro não publica o servidor na internet. O script solicita sudo quando necessário.'),
 note('Resultado esperado','O instalador cria HTTPS, banco SQLite, primeiro administrador e serviço '+mono('java-assessment-dashboard')+'. A instalação termina com '+mono('INSTALLED: https://...')+' após verificar a saúde do serviço.'),
 p('Destino: '+mono('/opt/java-assessment-dashboard')+'. Este pacote instala um dashboard novo e recusa substituir uma instalação existente.',SMALL))

section('03','Abrir e configurar o painel','Faça o primeiro acesso e crie os ambientes antes de distribuir qualquer agente.')
add(p('1. Consultar o acesso temporário',SUB),Code('Servidor do dashboard', 'sudo cat /root/java-assessment-dashboard-admin.txt'),
 p('Abra a URL exibida e entre com email e senha temporária. A senha expira em <b>24 horas</b> e a troca é obrigatória no primeiro login. Escolha uma senha pessoal de 8 a 128 caracteres.'),
 note('Confiança no certificado','A instalação padrão cria sua própria CA. A equipe de TI deve importar a CA pública '+mono('/opt/java-assessment-dashboard/conf/agent-ca.pem')+' no repositório de confiança adequado. Os agentes recebem essa CA no pacote. Certificado corporativo: etapa 07.'),
 p('2. Criar as contas da equipe',SUB),
 p('Em <b>Administração → Usuários</b>, cadastre nome, email e perfil. Use <b>Administrador</b> para quem gerencia o ambiente e <b>Leitura</b> para quem consulta o inventário. Cada pessoa usa sua própria conta.'),
 p('3. Criar os ambientes',SUB),
 bullet('No menu de ambientes, crie os grupos desejados, por exemplo Desenvolvimento, Homologação e Produção.'),
 bullet('Ajuste os limites e a periodicidade da coleta conforme a política do cliente.'),
 p('4. Gerar o pacote de cada ambiente',SUB),
 p('Abra o ambiente e selecione <b>Baixar agente</b>. Defina validade, número de cadastros autorizados e se deseja a primeira coleta automática.'),
 table([('O ZIP contém','URL do dashboard do cliente, CA pública e autorização temporária de cadastro vinculada ao ambiente.'),('Distribuição','Use um canal protegido. Um pacote expirado, revogado ou com limite esgotado deve ser substituído por um novo download.')]),
 note('Use o dashboard do próprio cliente','Baixe os agentes nessa instalação. Isso direciona os novos cadastros ao servidor e ao ambiente corretos.'))

section('04','Instalar os agentes','Execute em cada máquina monitorada. O ZIP pode estar em qualquer pasta adequada; o usuário administrativo não precisa se chamar opc.')
add(p('1. Extrair o pacote do ambiente',SUB),
 p('O exemplo usa Produção. Preencha X.Y.Z e use o <b>nome real do ZIP</b> baixado. Extraia em uma pasta ainda inexistente.',SMALL),
 Code('Máquina monitorada', r'''umask 077
VERSAO="X.Y.Z"
unzip "assessment-producao-$VERSAO.zip" \
  -d "agent-installer-$VERSAO"
cd "agent-installer-$VERSAO"
sudo /bin/sh ./install.sh --system-root'''),
 note('Caminho operacional fixo',mono('/opt/java-assessment-collector')+'<br/>O instalador usa esse destino, independentemente da pasta onde o ZIP foi extraído.'),
 p('2. Usar uma JVM existente e protegida',SUB),
 p('O instalador procura uma JVM compatível, de propriedade do root e protegida contra escrita por outros usuários. Não instala Java e não modifica permissões das aplicações. Um JRE é suficiente.'),
 p('Se precisar escolher explicitamente a JVM:',SMALL),
 Code('Opcional - informe um caminho real e protegido', r'''sudo /bin/sh ./install.sh --system-root \
  --java /caminho/protegido/bin/java'''),
 p('3. Entender a execução',SUB),
 p('O coletor usa '+mono('-Xms16m -Xmx96m')+'. A memória total inclui estruturas nativas e subprocessos. O agendador consulta tarefas aproximadamente a cada minuto; a varredura só acontece quando há tarefa autorizada.'),
 p('Linux moderno usa timer systemd; Linux antigo usa serviço SysV. O registro da máquina e o contato operacional podem aparecer antes do primeiro inventário.'),
 note('Atualizações posteriores','Prefira o painel para instalações elegíveis; veja a etapa 06. Para trocar de dashboard, planeje um novo cadastro com a opção '+mono('--switch-dashboard')+'.'))

section('05','Confirmar a primeira coleta','Verifique o serviço, a versão e o resultado no painel. Um heartbeat comprova contato; a coleta precisa de confirmação própria.')
add(p('No servidor do dashboard',SUB),Code('Serviço e log recente', r'''sudo systemctl status java-assessment-dashboard --no-pager
sudo journalctl -u java-assessment-dashboard -n 60 --no-pager'''),
 p('Na máquina com agente',SUB),Code('Versão instalada e diagnóstico', r'''sudo /bin/sh /opt/java-assessment-collector/agent-tool.sh version
sudo /opt/java-assessment-collector/agent-tool.sh diagnose \
  /opt/java-assessment-collector/conf/collector.properties
sudo tail -n 60 /opt/java-assessment-collector/logs/install.log'''),
 p('Espere '+mono('AGENT_VERSION=')+' com a versão instalada. O comando usa o código ativo, inclusive após atualização V3. Confira o agendador conforme o sistema:',SMALL),
 Code('Com systemd', r'''sudo systemctl is-active java-assessment-collector.timer'''),
 Code('Com SysV', 'sudo /sbin/service java-assessment-collector status'),
 p('No dashboard do cliente',SUB),
 bullet('Confirme a máquina no ambiente correto, versão esperada e contato recente.'),
 bullet('Solicite um assessment e acompanhe <b>Solicitações</b> até a conclusão. Se a coleta inicial foi automática, acompanhe o pedido já criado.'),
 bullet('Confira instalações Java, aplicações e datasources conhecidos. Abra eventuais observações de cobertura.'),
 note('Mensagem normal',mono('ASSESSMENT_NO_JOB')+' significa que o agente está conectado e sem tarefa pendente. Não é erro de instalação.'))

section('06','Atualizações e relatórios','Acompanhe a atualização, a coleta e a análise em etapas distintas. Cada uma tem seu próprio resultado.')
add(p('1. Revisar e atualizar os agentes',SUB),
 p('Após atualizar o dashboard, abra <b>Revisar agentes</b>. Confira a versão disponível e a elegibilidade. Selecione uma máquina ou as máquinas do ambiente. Cada máquina tem 5 minutos para iniciar na sua vez; sem resposta, o lote segue. Crie novo pedido quando ela voltar.'),
 p('Instalações V3 e perfis legados conhecidos podem receber atualização completa pelo painel quando elegíveis. O pacote assinado pode incluir scripts e bibliotecas previstos; o fluxo preserva cadastro, configuração e confiança HTTPS.'),
 note('Atenção: atualize também os agentes','Publicar o dashboard não atualiza as máquinas automaticamente. Sem suporte elegível, use o pacote configurado do ambiente e o instalador completo com --system-root. Comece por uma máquina.'),
 p('2. Acompanhar o pedido e o assessment',SUB),
 p('Mantenha a opção de assessment após atualização habilitada. Ao confirmar, o painel abre a <b>Solicitação</b>. Aguarde a confirmação da versão final e abra o assessment vinculado. Fechar a tela não cancela a operação.'),
 p('Se aparecer <b>Aguardando confirmação</b>, o resultado ainda é desconhecido. Consulte o motivo no mesmo pedido. Não apague arquivos nem force uma reinstalação enquanto houver uma operação pendente.',SMALL),
 p('3. Consultar e exportar relatórios',SUB),
 p('Em <b>Relatórios</b>, escolha Máquinas, Java, Servidores Java, JDBC ou Resumo por ambiente. Selecione ambiente, filtros e colunas; exporte PDF ou CSV. As exportações respeitam a consulta e incluem todas as páginas.'),
 p('Todos os modelos incluem JDBC. Combine datasource, banco, driver e versão, classe, referência, servidor, aplicação, tipo, estado e presença de JDBC. Em Servidores Java, confira também IP, Java associado e JDBC do servidor.'),
 note('Validar o resultado','Compare uma aplicação conhecida com seu servidor e datasource. JDBC do servidor usa os vínculos registrados; JDBC da máquina mostra o contexto do host. Recarregar a tela não solicita uma nova coleta.'),
 p('Dashboard com executor independente: em Administração, envie o ZIP web-update da entrega, revise a preparação e confirme Atualizar e reiniciar com sua senha. Aguarde o retorno e confira versão e log. Sem executor, use o script compatível para a primeira adoção.',SMALL))

section('07','Operação e homologação','Organize a confiança HTTPS, os backups e uma validação representativa antes de ampliar a instalação.')
add(p('Certificado corporativo - opcional na instalação nova',SUB),
 p('Use certificado para o DNS/IP escolhido, cadeia PEM e chave RSA PKCS#8 sem senha. Acrescente as opções à instalação inicial:',SMALL),
 Code('Substitua endereço, conta e caminhos', r'''./install.sh --public-url https://dashboard.exemplo.com \
  --admin-name "Administrador" --admin-email admin@exemplo.com \
  --tls-cert /caminho/fullchain.pem \
  --tls-key /caminho/privkey.pem --agent-ca /caminho/ca.pem'''),
 p('Na geração automática, a chave da CA fica em '+mono('/root/java-assessment-dashboard-ca.key')+'. Proteja-a com os backups; somente a CA pública é distribuída aos agentes.',SMALL),
 p('Backup consistente do banco',SUB),
 p('Use um nome novo para cada cópia. O utilitário verifica o banco e recusa sobrescrever um destino existente.',SMALL),
 Code('Servidor do dashboard', r'''sudo install -d -m 700 /root/assessment-backups
sudo /opt/java-assessment-dashboard/database.sh backup \
  /opt/java-assessment-dashboard/data/inventory.sqlite \
  /root/assessment-backups/inventory-AAAAMMDD-HHMM.sqlite'''),
 p('Troque '+mono('AAAAMMDD-HHMM')+' pela data e hora. Espere '+mono('BACKUP_OK')+'. Guarde também configuração e chaves TLS. Não copie apenas o arquivo SQLite com o serviço em execução.',SMALL),
 p('Validação antes da expansão',SUB),
 bullet('Comece com uma máquina de cada família de SO/JVM e compare o inventário com o que já existe nela.'),
 bullet('Valide HTTPS, primeiro acesso, coleta, atualização e exportações em uma máquina representativa antes de ampliar a implantação.'),
 note('Limites conhecidos desta entrega','O bytecode é compatível com Java 6, mas isso não certifica todas as combinações de Linux/JVM. Ambientes de 32 bits ainda não têm validação suficiente. Meça consumo e duração da coleta no escopo real.'),
 p('Se algo falhar, interrompa a sequência e consulte os logs. Ao solicitar suporte, compartilhe versão, etapa e mensagem de erro, sem senhas, tokens, chaves privadas ou banco.',SMALL))

OUT.parent.mkdir(parents=True,exist_ok=True)
DELIVERY.mkdir(parents=True,exist_ok=True)
(ROOT/'tmp/pdfs/documentation').mkdir(parents=True,exist_ok=True)
Doc().build(story)
from pypdf import PdfReader
reader=PdfReader(OUT);assert len(reader.pages)==8,f'Unexpected pages: {len(reader.pages)}'
alltext='\n'.join(page.extract_text() for page in reader.pages)
assert 'X.Y.Z' in alltext and '96 MiB' in alltext and '1,5 GiB' in alltext
assert '\ufffd' not in alltext and 'PILOTO' not in alltext and '1.0 Beta' not in alltext
assert len(reader.outline)==7
shutil.copy2(OUT,DELIVERY/OUT.name)
(ROOT/'tmp/pdfs/documentation/commands.txt').write_text('JAVA ASSESSMENT - COMANDOS DO GUIA\nLeia o PDF antes de executar. Substitua os valores de exemplo.\nExecute somente a seção aplicável, interrompendo ao encontrar erro.\n\n'+'\n\n'.join('['+c['label']+']\n'+c['code'] for c in commands)+'\n')
(ROOT/'tmp/pdfs/documentation/guide-checks.json').write_text(json.dumps({'pages':len(reader.pages),'bookmarks':len(reader.outline),'commandBlocks':len(commands),'selectableText':True,'output':str(OUT)},indent=2)+'\n')
print(OUT)
