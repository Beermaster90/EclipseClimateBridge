import sys
sys.path.insert(0, r'G:\My Drive\Mitsukannyprojekti\tools')
from parse_pcap import read_pcap, parse_ipv4_tcp

path = sys.argv[1]
linktype, packets = read_pcap(path)
print(f'total packets: {len(packets)}, linktype={linktype}')
ips = {}
ports = {}
for ts_a, ts_b, pkt in packets:
    info = parse_ipv4_tcp(pkt, linktype)
    if info is None:
        continue
    key = (info.get('src_ip'), info.get('dst_ip'))
    ips[key] = ips.get(key, 0) + 1
    for p in (info['src_port'], info['dst_port']):
        ports[p] = ports.get(p, 0) + 1
print('ip pairs:', ips)
print('ports:', sorted(ports.items(), key=lambda x: -x[1])[:20])
