package com.github.luohaha.paxos.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.github.luohaha.paxos.core.Accepter.Instance;
import com.github.luohaha.paxos.packet.LearnRequest;
import com.github.luohaha.paxos.packet.LearnResponse;
import com.github.luohaha.paxos.packet.Packet;
import com.github.luohaha.paxos.packet.PacketBean;
import com.github.luohaha.paxos.utils.CommClient;
import com.github.luohaha.paxos.utils.CommClientImpl;
import com.github.luohaha.paxos.utils.CommServer;
import com.github.luohaha.paxos.utils.CommServerImpl;
import com.github.luohaha.paxos.utils.ConfReader;
import com.github.luohaha.paxos.utils.NonBlockServerImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class Learner {

	// learner's id
	private int id;

	// accepter's number
	private int accepterNum;

	// accepters
	private List<InfoObject> learners;

	// 学习到的临时状态 instanceid -> id -> value
	private Map<Integer, Map<Integer, String>> tmpState = new HashMap<>();

	// 学习的状态
	private Map<Integer, String> state = new HashMap<>();

	// 学习到的instance
	private int currentInstance = 1;

	// learner配置信息
	private InfoObject my;

	/**
	 * 全局配置文件信息
	 */
	private ConfObject confObject;

	/**
	 * 当前节点的accepter
	 */
	private Accepter accepter;

	/**
	 * 定时线程
	 */
	private ScheduledExecutorService service = Executors.newScheduledThreadPool(1);

	/**
	 * 状态执行者
	 */
	private PaxosCallback executor;

	// 组id
	private int groupId;

	private Gson gson = new Gson();
	
	// 客户端
	private CommClient client;
	
	// 消息队列，保存packetbean
	private BlockingQueue<PacketBean> msgQueue = new LinkedBlockingQueue<>();

	public Learner(int id, List<InfoObject> learners, InfoObject my, ConfObject confObject, Accepter accepter,
			PaxosCallback executor, int groupId, CommClient client) {
		super();
		this.id = id;
		this.accepterNum = learners.size();
		this.learners = learners;
		this.my = my;
		this.confObject = confObject;
		this.accepter = accepter;
		this.executor = executor;
		this.groupId = groupId;
		this.client = client;
		service.scheduleAtFixedRate(() -> {
			// 广播学习请求
			sendRequest(this.id, this.currentInstance);
		} , confObject.getLearningInterval(), confObject.getLearningInterval(), TimeUnit.MILLISECONDS);
		new Thread(() -> {
			while (true) {
				try {
					PacketBean msg = msgQueue.take();
					recvPacket(msg);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}).start();
	}
	
	/**
	 * 向消息队列中插入packetbean
	 * @param bean
	 * @throws InterruptedException
	 */
	public void sendPacket(PacketBean bean) throws InterruptedException {
		this.msgQueue.put(bean);
	}

	/**
	 * 处理接收到的packetbean
	 * 
	 * @param bean
	 */
	public void recvPacket(PacketBean bean) {
		switch (bean.getType()) {
		case "LearnRequest":
			LearnRequest request = gson.fromJson(bean.getData(), LearnRequest.class);
			String value = "";
			if (accepter.getAcceptedValue().containsKey(request.getInstance()))
				value = accepter.getAcceptedValue().get(request.getInstance()).toString();
			sendResponse(request.getId(), request.getInstance(), value);
			break;
		case "LearnResponse":
			LearnResponse response = gson.fromJson(bean.getData(), LearnResponse.class);
			onResponse(response.getId(), response.getInstance(), response.getValue());
			break;
		default:
			System.err.println("Unknown Type!");
			break;
		}
	}

	/**
	 * 发送请求
	 * 
	 * @param instance
	 */
	private void sendRequest(int id, int instance) {
		this.tmpState.remove(instance);
		PacketBean packetBean = new PacketBean("LearnRequest", gson.toJson(new LearnRequest(id, instance)));
		String data = gson.toJson(new Packet(packetBean, this.groupId, WorkerType.LEARNER));
		learners.forEach((info) -> {
			try {
				this.client.sendTo(info.getHost(), info.getPort(), data.getBytes());
			} catch (Exception e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		});
	}

	/**
	 * 发送响应
	 * 
	 * @param peerId
	 *            对端的id
	 * @param instance
	 * @param value
	 */
	private void sendResponse(int peerId, int instance, String value) {
		InfoObject peer = getSpecLearner(peerId);
		try {
			PacketBean packetBean = new PacketBean("LearnResponse",
					gson.toJson(new LearnResponse(id, instance, value)));
			this.client.sendTo(peer.getHost(), peer.getPort(),
					gson.toJson(new Packet(packetBean, this.groupId, WorkerType.LEARNER)).getBytes());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * 获取id为特定值的learner信息
	 * 
	 * @param id
	 * @return
	 */
	private InfoObject getSpecLearner(int id) {
		for (InfoObject each : this.learners) {
			if (each.getId() == id) {
				return each;
			}
		}
		return null;
	}

	/**
	 * 响应返回值
	 * 
	 * @param peerId
	 * @param instance
	 * @param value
	 */
	private void onResponse(int peerId, int instance, String value) {
		if (!this.tmpState.containsKey(instance)) {
			this.tmpState.put(instance, new HashMap<>());
		}
		if (value == null || value.length() == 0)
			return;
		Map<Integer, String> map = this.tmpState.get(instance);
		map.put(peerId, value);
		Map<String, Integer> count = new HashMap<>();
		map.forEach((k, v) -> {
			if (!count.containsKey(v)) {
				count.put(v, 1);
			} else {
				count.put(v, count.get(v) + 1);
			}
		});
		count.forEach((k, v) -> {
			if (v >= this.accepterNum / 2 + 1) {
				this.state.put(instance, k);
				// 当learner学习成功时，让accepter也去同步这个状态
				this.accepter.getAcceptedValue().put(instance, k);
				Accepter.Instance acceptInstance = this.accepter.getInstanceState().get(instance);
				if (acceptInstance == null) {
					this.accepter.getInstanceState().put(instance, new Accepter.Instance(1, k, 1));
				} else {
					acceptInstance.setValue(k);
				}
				if (instance == currentInstance) {
					// 调用paxos状态执行者
					this.executor.callback(k);
					currentInstance++;
				}
			}
		});
	}
}
