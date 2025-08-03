#!/usr/bin/env python3
"""
WebSocket 부하 테스트 자동화 스크립트
"""
import asyncio
import json
import time
import statistics
from datetime import datetime, timedelta
import websockets
import stomper
from concurrent.futures import ThreadPoolExecutor

class WebSocketLoadTest:
    def __init__(self, base_url="ws://10.26.252.48:8090/ws-tracks"):
        self.base_url = base_url
        self.results = []
        self.active_connections = 0
        
    async def single_client_test(self, client_id, duration_seconds=60):
        """단일 클라이언트 테스트"""
        start_time = time.time()
        messages_received = 0
        bytes_received = 0
        errors = 0
        
        try:
            async with websockets.connect(self.base_url) as websocket:
                self.active_connections += 1
                print(f"Client {client_id}: Connected")
                
                # STOMP CONNECT
                connect_frame = stomper.connect(host='/', accept_version='1.2')
                await websocket.send(connect_frame)
                
                # Subscribe to data channel
                sub_frame = stomper.subscribe('/user/queue/tracks/data', client_id)
                await websocket.send(sub_frame)
                
                # Send query request
                query_request = {
                    "startTime": (datetime.now() - timedelta(days=1)).isoformat(),
                    "endTime": datetime.now().isoformat(),
                    "viewport": {
                        "minLon": 124.0,
                        "maxLon": 132.0,
                        "minLat": 33.0,
                        "maxLat": 38.0
                    },
                    "filters": {
                        "minDistance": 10,
                        "minSpeed": 5
                    },
                    "chunkSize": 2000
                }
                
                send_frame = stomper.send('/app/tracks/query', json.dumps(query_request))
                await websocket.send(send_frame)
                
                # Receive messages
                while time.time() - start_time < duration_seconds:
                    try:
                        message = await asyncio.wait_for(websocket.recv(), timeout=1.0)
                        messages_received += 1
                        bytes_received += len(message)
                        
                        # Parse STOMP frame
                        frame = stomper.Frame()
                        frame.parse(message)
                        
                        if frame.cmd == 'MESSAGE':
                            data = json.loads(frame.body)
                            if data.get('type') == 'complete':
                                print(f"Client {client_id}: Query completed")
                                break
                                
                    except asyncio.TimeoutError:
                        continue
                    except Exception as e:
                        errors += 1
                        print(f"Client {client_id}: Error - {e}")
                
        except Exception as e:
            errors += 1
            print(f"Client {client_id}: Connection error - {e}")
        finally:
            self.active_connections -= 1
            
        # Calculate results
        elapsed_time = time.time() - start_time
        result = {
            'client_id': client_id,
            'duration': elapsed_time,
            'messages': messages_received,
            'bytes': bytes_received,
            'errors': errors,
            'msg_per_sec': messages_received / elapsed_time if elapsed_time > 0 else 0,
            'mbps': (bytes_received / 1024 / 1024) / elapsed_time if elapsed_time > 0 else 0
        }
        
        self.results.append(result)
        return result
    
    async def run_load_test(self, num_clients=10, duration=60):
        """병렬 부하 테스트 실행"""
        print(f"Starting load test with {num_clients} clients for {duration} seconds...")
        
        tasks = []
        for i in range(num_clients):
            task = asyncio.create_task(self.single_client_test(i, duration))
            tasks.append(task)
            await asyncio.sleep(0.1)  # Stagger connections
        
        # Wait for all clients to complete
        await asyncio.gather(*tasks)
        
        # Print summary
        self.print_summary()
    
    def print_summary(self):
        """테스트 결과 요약 출력"""
        print("\n" + "="*60)
        print("LOAD TEST SUMMARY")
        print("="*60)
        
        total_messages = sum(r['messages'] for r in self.results)
        total_bytes = sum(r['bytes'] for r in self.results)
        total_errors = sum(r['errors'] for r in self.results)
        avg_msg_per_sec = statistics.mean(r['msg_per_sec'] for r in self.results)
        avg_mbps = statistics.mean(r['mbps'] for r in self.results)
        
        print(f"Total Clients: {len(self.results)}")
        print(f"Total Messages: {total_messages:,}")
        print(f"Total Data: {total_bytes/1024/1024:.2f} MB")
        print(f"Total Errors: {total_errors}")
        print(f"Avg Messages/sec per client: {avg_msg_per_sec:.2f}")
        print(f"Avg Throughput per client: {avg_mbps:.2f} MB/s")
        print(f"Total Throughput: {avg_mbps * len(self.results):.2f} MB/s")
        
        # Error rate
        error_rate = (total_errors / len(self.results)) * 100 if self.results else 0
        print(f"Error Rate: {error_rate:.2f}%")
        
        # Success rate
        successful_clients = sum(1 for r in self.results if r['errors'] == 0)
        success_rate = (successful_clients / len(self.results)) * 100 if self.results else 0
        print(f"Success Rate: {success_rate:.2f}%")
        
        print("="*60)

async def main():
    # Test scenarios
    scenarios = [
        {"clients": 10, "duration": 60, "name": "Light Load"},
        {"clients": 50, "duration": 120, "name": "Medium Load"},
        {"clients": 100, "duration": 180, "name": "Heavy Load"}
    ]
    
    for scenario in scenarios:
        print(f"\n{'='*60}")
        print(f"Running scenario: {scenario['name']}")
        print(f"{'='*60}")
        
        tester = WebSocketLoadTest()
        await tester.run_load_test(
            num_clients=scenario['clients'],
            duration=scenario['duration']
        )
        
        # Wait between scenarios
        print(f"\nWaiting 30 seconds before next scenario...")
        await asyncio.sleep(30)

if __name__ == "__main__":
    asyncio.run(main())
