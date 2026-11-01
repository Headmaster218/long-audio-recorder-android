"""Pinned pyftpdlib FTPS fixture; faults subclass callbacks, not the protocol/TLS implementation."""
import argparse,hashlib,json,logging,pathlib,time
from readiness import publish_ready
from OpenSSL import SSL
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import TLS_FTPHandler,TLS_DTPHandler
from pyftpdlib.servers import FTPServer

p=argparse.ArgumentParser();p.add_argument('scenario');p.add_argument('work');p.add_argument('cert');p.add_argument('key');p.add_argument('wrong_cert')
a=p.parse_args();work=pathlib.Path(a.work).resolve();remote=work/'remote';(remote/'fixture').mkdir(parents=True)
metrics={'scenario':a.scenario,'commands':[],'sessions':0,'control_tls':0,'data_tls':0,'data_bytes':0,'fault':False,'errors':[],'listeners':[]}
handlers=[];data_handlers=[]
wrong=SSL.Context(SSL.TLS_SERVER_METHOD);wrong.set_min_proto_version(SSL.TLS1_2_VERSION);wrong.use_certificate_chain_file(a.wrong_cert);wrong.use_privatekey_file(a.key)
logging.basicConfig(level=logging.WARNING)

class Data(TLS_DTPHandler):
    ac_in_buffer_size=1024
    def __init__(self,sock,cmd_channel):
        data_handlers.append(self)
        old=cmd_channel.ssl_context
        if a.scenario=='wrong_data_cert':cmd_channel.ssl_context=wrong
        try:super().__init__(sock,cmd_channel)
        finally:cmd_channel.ssl_context=old
        self._counted=False
    def handle_ssl_established(self):
        metrics['data_tls']+=1
        return super().handle_ssl_established()
    def handle_read_event(self):
        super().handle_read_event()
        if a.scenario=='partial_disconnect' and self.receive and self.tot_bytes_received>0 and not metrics['fault']:
            metrics['fault']=True
            self.close();self.cmd_channel.close()
    def close(self):
        if not getattr(self,'_counted',True):
            self._counted=True;metrics['data_bytes']+=self.get_transmitted_bytes()
        return super().close()

class Handler(TLS_FTPHandler):
    certfile=a.cert;keyfile=a.key;dtp_handler=Data
    tls_control_required=True;tls_data_required=True
    timeout=10;max_login_attempts=1;permit_foreign_addresses=False;permit_privileged_ports=False
    passive_ports=None;masquerade_address=None;use_sendfile=False
    def on_connect(self):
        if self.remote_ip!='127.0.0.1' or self.socket.getsockname()[0]!='127.0.0.1':raise AssertionError('nonloopback server channel')
        metrics['sessions']+=1
        if metrics['sessions']>3:raise AssertionError('session bound')
        self.fixture_session=metrics['sessions'];self.last_fixture_command='';self.delivery_reads=0;self.stalled=False
        handlers.append(self)
    def handle_ssl_established(self):
        metrics['control_tls']+=1
        return super().handle_ssl_established()
    def process_command(self,cmd,*args,**kwargs):
        arg=str(args[0]) if args else ''
        if cmd=='PASS':arg='[redacted]'
        protected=bool(self._ssl_established)
        if cmd in ('USER','PASS') and not protected:raise AssertionError('credential before authenticated control')
        metrics['commands'].append({'session':self.fixture_session,'cmd':cmd,'arg':arg,'tls':protected})
        if len(metrics['commands'])>192:raise AssertionError('command bound')
        self.last_fixture_command=cmd+' '+arg
        if cmd=='RETR' and arg.endswith('delivery.v1.txt'):self.delivery_reads+=1
        return super().process_command(cmd,*args,**kwargs)
    def ftp_AUTH(self,line):
        if a.scenario=='stalled_handshake':
            self.stalled=True;self.respond('234 AUTH TLS accepted; synthetic handshake stall.')
        else:super().ftp_AUTH(line)
    def handle_read(self):
        if getattr(self,'stalled',False):
            self.recv(4096)  # Fault: consume no application data and never complete TLS.
        else:super().handle_read()
    def respond(self,resp,*args,**kwargs):
        if a.scenario=='lost_final_reply' and resp.startswith('226 ') and getattr(self,'delivery_reads',0)==2 and self.last_fixture_command.startswith('RETR ') and not metrics['fault']:
            metrics['fault']=True;self.close();return
        return super().respond(resp,*args,**kwargs)
    def on_file_received(self,path):
        file=pathlib.Path(path)
        if file.name=='audio.wav' and not metrics['fault'] and a.scenario in ('corrupt_readback','truncated_readback'):
            payload=bytearray(file.read_bytes())
            if a.scenario=='corrupt_readback':payload[-1]^=1
            else:payload=payload[:-1]
            file.write_bytes(payload);metrics['fault']=True
    def handle_error(self):
        metrics['errors'].append('server handler exception')
        return super().handle_error()

authorizer=DummyAuthorizer();authorizer.add_user('synthetic','synthetic',str(remote),perm='elrwm')
Handler.authorizer=authorizer
server=FTPServer(('127.0.0.1',0),Handler);server.max_cons=4;server.max_cons_per_ip=4
server.handler.ssl_context.set_min_proto_version(SSL.TLS1_2_VERSION)
port=server.socket.getsockname()[1];metrics['listeners'].append(port)
publish_ready(work, '127.0.0.1', port)
started=time.monotonic()
try:
    while not (work/'stop').exists():
        if time.monotonic()-started>35:raise TimeoutError('fixture server deadline')
        if metrics['data_bytes']>2*1024*1024:raise RuntimeError('per-case data bound')
        live_listeners=sum(bool(getattr(obj,'accepting',False)) for obj in server.ioloop.socket_map.values())
        if live_listeners>2:raise AssertionError('simultaneous listener bound')
        for obj in list(server.ioloop.socket_map.values()):
            sock=getattr(obj,'socket',None)
            if sock is not None:
                try:
                    ip,local_port=sock.getsockname()[:2]
                    if ip!='127.0.0.1':raise AssertionError('nonloopback bound socket')
                    if hasattr(obj,'accepting') and obj.accepting and local_port not in metrics['listeners']:metrics['listeners'].append(local_port)
                except OSError:pass
        server.serve_forever(timeout=0.02,blocking=False,handle_exit=False)
finally:
    for handler in handlers:handler.close()
    for channel in data_handlers:channel.close()
    server.close_all()
    metrics['all_closed']=not server.ioloop.socket_map
    metrics['remote_files']={str(f.relative_to(remote)): {'size':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()}for f in remote.rglob('*')if f.is_file()}
    (work/'server-result.json').write_text(json.dumps(metrics,indent=2))
