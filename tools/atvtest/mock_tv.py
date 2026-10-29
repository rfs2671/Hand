"""A fake Android TV for testing the Hand Bridge client end to end.

Message layouts come from the real .proto definitions: they are read as plain data out of
the androidtvremote2 package's generated files (nothing from that package is executed), so a
mistake in our hand-written encoder shows up as a parse failure or a wrong field here.
"""
import ast, hashlib, json, os, re, socket, ssl, sys, threading, time, datetime
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

SRC = sys.argv[5]
WORK, PAIR_PORT, REMOTE_PORT = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
LOG = open(os.path.join(WORK, 'mock.log'), 'a', buffering=1)

def log(**kw):
    LOG.write(json.dumps(kw) + '\n')

def load_fd(path):
    src = open(path).read()
    m = re.search(r"AddSerializedFile\((b'(?:[^'\\]|\\.)*'|b\"(?:[^\"\\]|\\.)*\")\)", src)
    fd = descriptor_pb2.FileDescriptorProto(); fd.ParseFromString(ast.literal_eval(m.group(1))); return fd

pool = descriptor_pool.DescriptorPool()
pool.Add(load_fd(os.path.join(SRC, 'polo_pb2.py')))
pool.Add(load_fd(os.path.join(SRC, 'remotemessage_pb2.py')))
Outer = message_factory.GetMessageClass(pool.FindMessageTypeByName('polo.wire.protobuf.OuterMessage'))
RemoteMessage = message_factory.GetMessageClass(pool.FindMessageTypeByName('remote.RemoteMessage'))

# --- server certificate (like a TV: self-signed, name and MAC in the common name)
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'atvremote/mock/mock/Mock Projector/AA:BB:CC:DD:EE:FF')])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(7).not_valid_before(now).not_valid_after(now + datetime.timedelta(days=3650))
        .sign(key, hashes.SHA256()))
open(os.path.join(WORK, 'server.pem'), 'wb').write(cert.public_bytes(serialization.Encoding.PEM))
open(os.path.join(WORK, 'server.key'), 'wb').write(key.private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
open(os.path.join(WORK, 'server.sha256'), 'w').write(hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest())

def ctx(trusted_pem=None):
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain(os.path.join(WORK, 'server.pem'), os.path.join(WORK, 'server.key'))
    c.verify_mode = ssl.CERT_REQUIRED
    if os.environ.get('MOCK_TLS12'):  # older TVs only speak TLS 1.2
        c.maximum_version = ssl.TLSVersion.TLSv1_2
    if trusted_pem:
        c.load_verify_locations(cafile=trusted_pem)
    return c

# A real TV lets any phone reach the pairing port, but only paired phones reach the remote port.
# (Python can't verify an unknown self-signed cert, so the pairing port trusts the test's client.pem.)
pair_ctx = ctx(os.path.join(WORK, 'client.pem'))
remote_ctx = ctx()

def read_frame(s):
    n, shift = 0, 0
    while True:
        b = s.recv(1)
        if not b: return None
        n |= (b[0] & 0x7F) << shift
        if not b[0] & 0x80: break
        shift += 7
    buf = b''
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk: raise EOFError('mid-frame')
        buf += chunk
    return buf

def send_frame(s, msg):
    data = msg.SerializeToString(); n = len(data); out = bytearray()
    while n > 0x7F: out.append((n & 0x7F) | 0x80); n >>= 7
    out.append(n)
    s.sendall(bytes(out) + data)

def nums(c):
    p = c.public_key().public_numbers(); return p.n, p.e

def expected_secret(client_der, server_cert, code):
    cn, ce = nums(x509.load_der_x509_certificate(client_der)); sn, se = nums(server_cert)
    h = hashlib.sha256()          # same construction the reference python client uses
    h.update(bytes.fromhex(f'{cn:X}')); h.update(bytes.fromhex(f'0{ce:X}'))
    h.update(bytes.fromhex(f'{sn:X}')); h.update(bytes.fromhex(f'0{se:X}'))
    h.update(bytes.fromhex(code[2:]))
    return h.digest()

def pair_conn(s):
    client_der = s.getpeercert(binary_form=True)
    m = Outer(); m.ParseFromString(read_frame(s))
    assert m.protocol_version == 2 and m.status == 200, m
    assert m.HasField('pairing_request') and m.pairing_request.service_name == 'atvremote', m
    log(ev='pairing_request', client_name=m.pairing_request.client_name)
    r = Outer(protocol_version=2, status=200); r.pairing_request_ack.server_name = 'Mock Projector'; send_frame(s, r)
    m = Outer(); m.ParseFromString(read_frame(s))
    assert m.HasField('options') and m.options.input_encodings[0].type == 3 and m.options.input_encodings[0].symbol_length == 6, m
    assert m.options.preferred_role == 1, m
    r = Outer(protocol_version=2, status=200); e = r.options.output_encodings.add(); e.type = 3; e.symbol_length = 6
    r.options.preferred_role = 2; send_frame(s, r)
    m = Outer(); m.ParseFromString(read_frame(s))
    assert m.HasField('configuration') and m.configuration.encoding.type == 3 and m.configuration.client_role == 1, m
    # pick a code the TV "displays": suffix fixed, prefix = first byte of the resulting hash
    server_cert = cert
    suffix = 'A1B2'
    first = expected_secret(client_der, server_cert, '00' + suffix)[0]
    code = f'{first:02X}{suffix}'
    open(os.path.join(WORK, 'code.txt'), 'w').write(code)
    r = Outer(protocol_version=2, status=200); r.configuration_ack.SetInParent(); send_frame(s, r)
    f = read_frame(s)
    if f is None: log(ev='pair_aborted'); return
    m = Outer(); m.ParseFromString(f)
    assert m.HasField('secret'), m
    good = m.secret.secret == expected_secret(client_der, server_cert, code)
    log(ev='secret', ok=good)
    if good:  # pairing succeeded: from now on the remote port trusts this client
        remote_ctx.load_verify_locations(cadata=ssl.DER_cert_to_PEM_cert(client_der))
    r = Outer(protocol_version=2, status=200 if good else 402)
    if good: r.secret_ack.secret = m.secret.secret
    send_frame(s, r)

def remote_conn(s):
    log(ev='remote_connected')
    m = RemoteMessage(); c = m.remote_configure; c.code1 = 623
    c.device_info.model = 'MockProjector'; c.device_info.vendor = 'HandTest'; c.device_info.app_version = '1.2.3'
    send_frame(s, m)
    r = RemoteMessage(); r.ParseFromString(read_frame(s))
    assert r.HasField('remote_configure'), r
    ci = r.remote_configure
    log(ev='client_configure', code1=ci.code1, package=ci.device_info.package_name, version=ci.device_info.app_version,
        u1=ci.device_info.unknown1, u2=ci.device_info.unknown2)
    m = RemoteMessage(); m.remote_set_active.active = 622; send_frame(s, m)
    r = RemoteMessage(); r.ParseFromString(read_frame(s))
    assert r.HasField('remote_set_active'), r
    log(ev='client_set_active', active=r.remote_set_active.active)
    m = RemoteMessage(); m.remote_start.started = True; send_frame(s, m)
    m = RemoteMessage(); v = m.remote_set_volume_level; v.volume_max = 100; v.volume_level = 33; v.volume_muted = False; send_frame(s, m)
    m = RemoteMessage(); m.remote_ping_request.val1 = 7; send_frame(s, m)
    while True:
        f = read_frame(s)
        if f is None: log(ev='remote_closed'); return
        r = RemoteMessage(); r.ParseFromString(f)
        if r.HasField('remote_ping_response'): log(ev='ping_response', val1=r.remote_ping_response.val1)
        elif r.HasField('remote_key_inject'):
            k = r.remote_key_inject; log(ev='key', code=k.key_code, direction=k.direction)
        elif r.HasField('remote_app_link_launch_request'): log(ev='app', link=r.remote_app_link_launch_request.app_link)
        else: log(ev='other', text=str(r))

def serve(port, handler, c):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('127.0.0.1', port)); srv.listen(5)
    def accept():
        while True:
            raw, _ = srv.accept()
            def run(raw=raw):
                try:
                    s = c.wrap_socket(raw, server_side=True)
                    handler(s)
                except ssl.SSLError as e:
                    log(ev='tls_rejected', port=port, error=str(e)[:80])
                except Exception as e:
                    log(ev='error', port=port, error=repr(e)[:200])
            threading.Thread(target=run, daemon=True).start()
    threading.Thread(target=accept, daemon=True).start()

serve(PAIR_PORT, pair_conn, pair_ctx)
serve(REMOTE_PORT, remote_conn, remote_ctx)
open(os.path.join(WORK, 'ready'), 'w').write('1')
while True: time.sleep(1)
