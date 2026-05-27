#!/usr/bin/env python3
"""
加法脚本 - 传入两个数字参数，输出它们的和
用法: python add.py <数字1> <数字2>
例如: python add.py 5 3
输出: 8
"""

import sys

def add_numbers(a, b):
    """计算两个数的和"""
    return a + b

def main():
    # 检查参数数量
    if len(sys.argv) != 3:
        print("用法: python add.py <数字1> <数字2>")
        print("例如: python add.py 3 5")
        sys.exit(1)
    
    try:
        # 将参数转换为数字
        num1 = float(sys.argv[1])
        num2 = float(sys.argv[2])
        
        # 计算并输出结果
        result = add_numbers(num1, num2)
        
        # 如果结果是整数，则输出整数形式
        if result == int(result):
            print(int(result))
        else:
            print(result)
            
    except ValueError:
        print("错误: 请输入有效的数字")
        sys.exit(1)

if __name__ == "__main__":
    main()