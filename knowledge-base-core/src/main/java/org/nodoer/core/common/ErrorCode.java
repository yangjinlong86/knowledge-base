package org.nodoer.core.common;

public class ErrorCode {

	private final Integer code;

	private final String msg;

	public ErrorCode(Integer code, String message) {
		this.code = code;
		this.msg = message;
	}

	public Integer getCode() {
		return code;
	}

	public String getMsg() {
		return msg;
	}

}
