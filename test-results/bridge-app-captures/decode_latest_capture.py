import sys
sys.path.insert(0, r'G:\My Drive\Mitsukannyprojekti\tools')
from parse_pcap import read_pcap, parse_ipv4_tcp

def try_key(payload, key):
    decoded = bytes(b ^ key for b in payload)
    if len(decoded) < 2:
        return None
    length_field = decoded[1]
    total_len = length_field + 2
    if total_len != len(decoded):
        return None
    checksum = 0
    for i in range(total_len - 1):
        checksum = (checksum + decoded[i]) & 0xFF
    if checksum != decoded[-1]:
        return None
    return decoded

def brute(payload):
    for key in range(256):
        result = try_key(payload, key)
        if result:
            return result, key
    return None, None

def main():
    path = sys.argv[1]
    port_filter = 8080
    linktype, packets = read_pcap(path)
    idx = 0
    base_ts = None
    for ts_a, ts_b, pkt in packets:
        idx += 1
        info = parse_ipv4_tcp(pkt, linktype)
        if info is None:
            continue
        if info['src_port'] != port_filter and info['dst_port'] != port_filter:
            continue
        if base_ts is None:
            base_ts = ts_a
        payload = info['payload']
        is_out = info['dst_port'] == port_filter
        direction = 'APP->CAR' if is_out else 'CAR->APP'
        t = ts_a - base_ts
        if not payload:
            print(f'#{idx:5d} t={t:7.3f}s {direction} len=0 (pure ACK/FIN/SYN, no payload)')
            continue
        decoded, key = brute(payload)
        if decoded:
            data = decoded[4:-1].hex() if len(decoded) > 5 else ''
            print(f'#{idx:5d} t={t:7.3f}s {direction} len={len(payload):3d} key={key:02x} '
                  f'cmd={decoded[0]:02x} type={decoded[2]:02x} reg={decoded[3]:02x} data={data}')
        else:
            print(f'#{idx:5d} t={t:7.3f}s {direction} len={len(payload):3d} raw={payload.hex()} (UNDECODED)')

if __name__ == '__main__':
    main()
