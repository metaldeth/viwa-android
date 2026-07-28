


#define DATA_REGISTER_EMPTY (1<<UDRE0)
#define RX_COMPLETE (1<<RXC0)
#define FRAMING_ERROR (1<<FE0)
#define PARITY_ERROR (1<<UPE0)
#define DATA_OVERRUN (1<<DOR0)

// USART3 Receiver buffer
#define RX_BUFFER_SIZE3 8
char rx_buffer3[RX_BUFFER_SIZE3];

#if RX_BUFFER_SIZE3 <= 256
unsigned char rx_wr_index3=0,rx_rd_index3=0;
#else
unsigned int rx_wr_index3=0,rx_rd_index3=0;
#endif

#if RX_BUFFER_SIZE3 < 256
unsigned char rx_counter3=0;
#else
unsigned int rx_counter3=0;
#endif

// This flag is set on USART3 Receiver buffer overflow
bit rx_buffer_overflow3;

// USART3 Receiver interrupt service routine
interrupt [USART3_RXC] void usart3_rx_isr(void)
{
char status,data;
status=UCSR3A;
data=UDR3;
if ((status & (FRAMING_ERROR | PARITY_ERROR | DATA_OVERRUN))==0)
   {
   rx_buffer3[rx_wr_index3++]=data;
#if RX_BUFFER_SIZE3 == 256
   // special case for receiver buffer size=256
   if (++rx_counter3 == 0) rx_buffer_overflow3=1;
#else
   if (rx_wr_index3 == RX_BUFFER_SIZE3) rx_wr_index3=0;
   if (++rx_counter3 == RX_BUFFER_SIZE3)
      {
      rx_counter3=0;
      rx_buffer_overflow3=1;
      }
#endif
   }
}

// Get a character from the USART3 Receiver buffer
#pragma used+
char getchar3(void)
{
char data;
while (rx_counter3==0);
data=rx_buffer3[rx_rd_index3++];
#if RX_BUFFER_SIZE3 != 256
if (rx_rd_index3 == RX_BUFFER_SIZE3) rx_rd_index3=0;
#endif
#asm("cli")
--rx_counter3;
#asm("sei")
return data;
}
#pragma used-

// USART3 Transmitter buffer
#define TX_BUFFER_SIZE3 8
char tx_buffer3[TX_BUFFER_SIZE3];

#if TX_BUFFER_SIZE3 <= 256
unsigned char tx_wr_index3=0,tx_rd_index3=0;
#else
unsigned int tx_wr_index3=0,tx_rd_index3=0;
#endif

#if TX_BUFFER_SIZE3 < 256
unsigned char tx_counter3=0;
#else
unsigned int tx_counter3=0;
#endif

// USART3 Transmitter interrupt service routine
interrupt [USART3_TXC] void usart3_tx_isr(void)
{
if (tx_counter3)
   {
   --tx_counter3;
   UDR3=tx_buffer3[tx_rd_index3++];
#if TX_BUFFER_SIZE3 != 256
   if (tx_rd_index3 == TX_BUFFER_SIZE3) tx_rd_index3=0;
#endif
   }
}

// Write a character to the USART3 Transmitter buffer
#pragma used+
void putchar3(char c)
{
while (tx_counter3 == TX_BUFFER_SIZE3);
#asm("cli")
if (tx_counter3 || ((UCSR3A & DATA_REGISTER_EMPTY)==0))
   {
   tx_buffer3[tx_wr_index3++]=c;
#if TX_BUFFER_SIZE3 != 256
   if (tx_wr_index3 == TX_BUFFER_SIZE3) tx_wr_index3=0;
#endif
   ++tx_counter3;
   }
else
   UDR3=c;
#asm("sei")
}
#pragma used-